package com.sprintiq.pms.modules.copilot.application;

import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.modules.copilot.application.llm.LlmClient;
import com.sprintiq.pms.modules.copilot.application.llm.LlmMessage;
import com.sprintiq.pms.modules.copilot.application.llm.LlmReply;
import com.sprintiq.pms.modules.copilot.application.llm.LlmUnavailableException;
import com.sprintiq.pms.modules.copilot.application.llm.ToolCall;
import com.sprintiq.pms.modules.copilot.application.llm.ToolSpec;
import com.sprintiq.pms.modules.copilot.application.tools.CopilotToolRegistry;
import com.sprintiq.pms.modules.copilot.application.tools.ToolContext;
import com.sprintiq.pms.modules.copilot.application.tools.ToolOutcome;
import com.sprintiq.pms.modules.copilot.domain.CopilotConversation;
import com.sprintiq.pms.modules.copilot.domain.CopilotMessage;
import com.sprintiq.pms.modules.copilot.domain.MessageRole;
import com.sprintiq.pms.modules.copilot.domain.ToolStep;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.CopilotStatusResponse;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.MessageResponse;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.SendMessageResponse;
import com.sprintiq.pms.security.AppProperties;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Copilot's agent loop: ask the model, run the tools it asks for, repeat, answer.
 *
 * <p>Deliberately not {@code @Transactional}. A turn spends most of its time waiting on
 * a third party, and a database transaction held open across that wait would pin a
 * connection for tens of seconds per question. Each tool runs in its own service's
 * transaction, and the turn is saved in one short one at the end.</p>
 */
@Slf4j
@Service
public class CopilotService {

    static final String SYSTEM_PROMPT_PATH = "prompts/copilot-system.txt";

    static final String NOT_CONFIGURED = "The Copilot is off: no AI provider key is configured. "
            + "Set ADVISOR_API_KEY to enable it — note that it sends the figures it looks up "
            + "to that provider.";

    private static final String OUT_OF_ROUNDS = "You have used all your tool calls for this "
            + "question. Answer now from the results above, and say what you could not check.";

    private final LlmClient llm;
    private final CopilotToolRegistry tools;
    private final ConversationService conversations;
    private final ProposalService proposals;
    private final AppProperties properties;
    private final String systemPrompt;

    public CopilotService(LlmClient llm, CopilotToolRegistry tools, ConversationService conversations,
                          ProposalService proposals, AppProperties properties) {
        this.llm = llm;
        this.tools = tools;
        this.conversations = conversations;
        this.proposals = proposals;
        this.properties = properties;
        this.systemPrompt = loadPrompt();
    }

    private static String loadPrompt() {
        // Unlike the advisor, a missing prompt fails startup: the Copilot can call tools
        // that propose changes, and running it without its rules is not a degraded mode.
        try (var in = new ClassPathResource(SYSTEM_PROMPT_PATH).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Copilot system prompt missing: " + SYSTEM_PROMPT_PATH, ex);
        }
    }

    public CopilotStatusResponse status() {
        return llm.isConfigured()
                ? new CopilotStatusResponse(true, llm.modelName(), null)
                : new CopilotStatusResponse(false, llm.modelName(), NOT_CONFIGURED);
    }

    public SendMessageResponse send(UUID conversationId, String content) {
        if (!llm.isConfigured()) {
            throw new ApplicationException(ErrorCode.AI_SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        }
        if (content == null || content.isBlank() || content.length() > CopilotDtos.MAX_MESSAGE_CHARS) {
            throw new ApplicationException(ErrorCode.VALIDATION_ERROR,
                    "A message must be between 1 and " + CopilotDtos.MAX_MESSAGE_CHARS + " characters.");
        }
        CopilotConversation conversation = conversations.requireOwned(conversationId);
        String question = content.strip();

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt + "\n\n" + contextLine()));
        // History is the visible thread only. Old tool results are not replayed: they
        // were true when fetched, and the model would quote them as if they still were.
        for (CopilotMessage turn : conversations.recentTurns(conversationId,
                properties.getAi().getCopilot().getHistoryTurns())) {
            messages.add(turn.getRole() == MessageRole.USER
                    ? LlmMessage.user(turn.getContent()) : LlmMessage.assistant(turn.getContent()));
        }
        messages.add(LlmMessage.user(question));

        ToolContext context = new ToolContext(conversationId, TenantContext.requireUserId());
        List<ToolStep> steps = new ArrayList<>();
        LlmReply answer;
        try {
            answer = runLoop(messages, context, steps);
        } catch (LlmUnavailableException ex) {
            conversations.saveTurn(conversation, question, null, null, List.of());
            throw new ApplicationException(ErrorCode.AI_SERVICE_UNAVAILABLE, ex.getMessage());
        }

        String text = answer.content() == null || answer.content().isBlank()
                ? "I could not put an answer together from what I found. Try asking more specifically."
                : answer.content().strip();
        ConversationService.SavedTurn saved = conversations.saveTurn(conversation, question, text,
                answer.model(), steps);
        proposals.attachToMessage(context.proposalIds(), saved.assistant().getId());

        List<CopilotDtos.ProposalResponse> offered = context.proposalIds().isEmpty() ? List.of()
                : proposals.forConversation(conversationId).stream()
                        .filter(p -> context.proposalIds().contains(p.id())).toList();
        MessageResponse user = ConversationService.toResponse(saved.user(), List.of());
        MessageResponse assistant = ConversationService.toResponse(saved.assistant(), offered);
        log.info("Copilot answered in conversation {} ({} tool calls, {} proposals, model={})",
                conversationId, steps.size(), offered.size(), answer.model());
        return new SendMessageResponse(ConversationService.summary(conversation), user, assistant);
    }

    private LlmReply runLoop(List<LlmMessage> messages, ToolContext context, List<ToolStep> steps) {
        List<ToolSpec> offered = tools.permittedSpecs();
        int maxRounds = properties.getAi().getCopilot().getMaxToolRounds();

        for (int round = 0; round < maxRounds; round++) {
            LlmReply reply = llm.chat(messages, offered);
            if (!reply.hasToolCalls()) {
                return reply;
            }
            messages.add(LlmMessage.assistantToolCalls(reply.content(), reply.toolCalls()));
            for (ToolCall call : reply.toolCalls()) {
                ToolOutcome outcome = tools.run(call.name(), call.argumentsJson(), context);
                steps.add(outcome.step());
                messages.add(LlmMessage.toolResult(call.id(), outcome.json()));
            }
        }

        // Out of rounds. Offer no tools, so the only thing left to do is answer.
        messages.add(LlmMessage.user(OUT_OF_ROUNDS));
        return llm.chat(messages, List.of());
    }

    private static String contextLine() {
        var ctx = TenantContext.get().orElseThrow();
        return "CONTEXT\n- Today: " + LocalDate.now(ZoneOffset.UTC)
                + "\n- The user's role: " + (ctx.roleCode() == null ? "unknown" : ctx.roleCode());
    }
}
