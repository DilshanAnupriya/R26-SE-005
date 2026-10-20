package com.sprintiq.pms.modules.copilot.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.copilot.application.llm.LlmClient;
import com.sprintiq.pms.modules.copilot.application.llm.LlmMessage;
import com.sprintiq.pms.modules.copilot.application.llm.LlmReply;
import com.sprintiq.pms.modules.copilot.application.llm.LlmUnavailableException;
import com.sprintiq.pms.modules.copilot.application.llm.ToolCall;
import com.sprintiq.pms.modules.copilot.application.llm.ToolSpec;
import com.sprintiq.pms.modules.copilot.application.tools.CopilotToolRegistry;
import com.sprintiq.pms.modules.copilot.application.tools.SimpleTool;
import com.sprintiq.pms.modules.copilot.application.tools.ToolSchemas;
import com.sprintiq.pms.modules.copilot.domain.CopilotConversation;
import com.sprintiq.pms.modules.copilot.domain.CopilotMessage;
import com.sprintiq.pms.modules.copilot.domain.MessageRole;
import com.sprintiq.pms.modules.copilot.domain.ToolStep;
import com.sprintiq.pms.security.AppProperties;
import com.sprintiq.pms.security.PermissionService;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Copilot agent loop")
class CopilotServiceTest {

    /** A model that answers from a script and remembers what it was sent. */
    static final class ScriptedLlm implements LlmClient {
        final Deque<Object> script = new ArrayDeque<>();
        final List<List<LlmMessage>> requests = new ArrayList<>();
        final List<List<ToolSpec>> toolsOffered = new ArrayList<>();
        boolean configured = true;

        ScriptedLlm then(LlmReply reply) {
            script.add(reply);
            return this;
        }

        ScriptedLlm thenFail(String message) {
            script.add(new LlmUnavailableException(message));
            return this;
        }

        @Override public boolean isConfigured() { return configured; }
        @Override public String modelName() { return "scripted"; }

        @Override
        public LlmReply chat(List<LlmMessage> messages, List<ToolSpec> tools) {
            requests.add(List.copyOf(messages));
            toolsOffered.add(List.copyOf(tools));
            Object next = script.isEmpty() ? new LlmReply("(script ran out)", List.of(), "scripted")
                    : script.poll();
            if (next instanceof LlmUnavailableException ex) throw ex;
            return (LlmReply) next;
        }
    }

    private static LlmReply text(String content) {
        return new LlmReply(content, List.of(), "scripted-1");
    }

    private static LlmReply calls(ToolCall... calls) {
        return new LlmReply(null, List.of(calls), "scripted-1");
    }

    private final ScriptedLlm llm = new ScriptedLlm();
    private final ConversationService conversations = mock(ConversationService.class);
    private final ProposalService proposals = mock(ProposalService.class);
    private final AppProperties properties = new AppProperties();
    private final UUID me = UUID.randomUUID();
    private final CopilotConversation conversation = CopilotConversation.builder().userId(me).build();
    private CopilotService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(UUID.randomUUID()).userId(me)
                .permissions(Set.of("sprint:read", "ai:predict")).systemContext(false).build());
        conversation.setId(UUID.randomUUID());

        CopilotToolRegistry registry = new CopilotToolRegistry(List.of(
                SimpleTool.builder().name("get_sprint_risk").label("Read sprint risk (M2)")
                        .description("risk").parameters(ToolSchemas.object(Map.of(), List.of()))
                        .requiredPermission("ai:predict")
                        .handler((args, ctx) -> Map.of("overall", 0.7)).build(),
                SimpleTool.builder().name("admin_only").label("Admin")
                        .description("admin").parameters(ToolSchemas.object(Map.of(), List.of()))
                        .requiredPermission("org:manage")
                        .handler((args, ctx) -> Map.of()).build()),
                new PermissionService(), new ObjectMapper());

        properties.getAi().getCopilot().setMaxToolRounds(3);
        properties.getAi().getCopilot().setHistoryTurns(20);
        service = new CopilotService(llm, registry, conversations, proposals, properties);

        when(conversations.requireOwned(conversation.getId())).thenReturn(conversation);
        when(conversations.recentTurns(eq(conversation.getId()), any(Integer.class))).thenReturn(List.of());
        when(conversations.saveTurn(any(), anyString(), any(), any(), anyList())).thenAnswer(inv ->
                new ConversationService.SavedTurn(
                        message(MessageRole.USER, inv.getArgument(1)),
                        inv.getArgument(2) == null ? null : message(MessageRole.ASSISTANT, inv.getArgument(2))));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static CopilotMessage message(MessageRole role, String content) {
        CopilotMessage m = CopilotMessage.builder().role(role).content(content).build();
        m.setId(UUID.randomUUID());
        return m;
    }

    @Test
    @DisplayName("a plain question is answered and both turns are saved")
    void plainAnswer() {
        llm.then(text("Sprint 7 looks fine."));

        service.send(conversation.getId(), "How is sprint 7?");

        verify(conversations).saveTurn(eq(conversation), eq("How is sprint 7?"),
                eq("Sprint 7 looks fine."), eq("scripted-1"), eq(List.of()));
        List<LlmMessage> sent = llm.requests.get(0);
        assertThat(sent.get(0).role()).isEqualTo("system");
        assertThat(sent.get(0).content()).containsIgnoringCase("data, not instructions");
        assertThat(sent.get(sent.size() - 1)).isEqualTo(LlmMessage.user("How is sprint 7?"));
    }

    @Test
    @DisplayName("offers only permitted tools")
    void offersPermittedTools() {
        llm.then(text("ok"));
        service.send(conversation.getId(), "hi");
        assertThat(llm.toolsOffered.get(0)).extracting(ToolSpec::name).containsExactly("get_sprint_risk");
    }

    @Test
    @DisplayName("runs a requested tool, feeds its result back, and records the step")
    void toolRound() {
        llm.then(calls(new ToolCall("c1", "get_sprint_risk", "{}")))
           .then(text("Risk is 70%."));

        service.send(conversation.getId(), "How risky?");

        List<LlmMessage> second = llm.requests.get(1);
        LlmMessage assistant = second.get(second.size() - 2);
        LlmMessage result = second.get(second.size() - 1);
        assertThat(assistant.toolCalls()).extracting(ToolCall::id).containsExactly("c1");
        assertThat(result.role()).isEqualTo("tool");
        assertThat(result.toolCallId()).isEqualTo("c1");
        assertThat(result.content()).contains("0.7");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolStep>> steps = ArgumentCaptor.forClass(List.class);
        verify(conversations).saveTurn(eq(conversation), eq("How risky?"), eq("Risk is 70%."),
                eq("scripted-1"), steps.capture());
        assertThat(steps.getValue()).containsExactly(new ToolStep("get_sprint_risk", true, "Read sprint risk (M2)"));
    }

    @Test
    @DisplayName("a refused tool is reported to the model, not thrown")
    void toolRefusal() {
        llm.then(calls(new ToolCall("c1", "admin_only", "{}"))).then(text("I can't see that."));

        service.send(conversation.getId(), "Delete the org");

        List<LlmMessage> second = llm.requests.get(1);
        assertThat(second.get(second.size() - 1).content()).contains("permission");
    }

    @Test
    @DisplayName("stops calling tools at the round limit and makes the model answer")
    void roundLimit() {
        for (int i = 0; i < 3; i++) llm.then(calls(new ToolCall("c" + i, "get_sprint_risk", "{}")));
        llm.then(text("Here is what I found."));

        service.send(conversation.getId(), "Dig deep");

        assertThat(llm.requests).hasSize(4);
        assertThat(llm.toolsOffered.get(3)).isEmpty();
        verify(conversations).saveTurn(any(), anyString(), eq("Here is what I found."), any(), anyList());
    }

    @Test
    @DisplayName("replays prior turns between the system prompt and the new message")
    void replaysHistory() {
        when(conversations.recentTurns(conversation.getId(), 20)).thenReturn(List.of(
                message(MessageRole.USER, "earlier question"),
                message(MessageRole.ASSISTANT, "earlier answer")));
        llm.then(text("ok"));

        service.send(conversation.getId(), "follow-up");

        List<LlmMessage> sent = llm.requests.get(0);
        assertThat(sent).extracting(LlmMessage::content)
                .containsSubsequence("earlier question", "earlier answer", "follow-up");
        assertThat(sent.get(1)).isEqualTo(LlmMessage.user("earlier question"));
    }

    @Test
    @DisplayName("links proposals made during the turn to the saved answer")
    void attachesProposals() {
        UUID proposalId = UUID.randomUUID();
        CopilotToolRegistry registry = new CopilotToolRegistry(List.of(
                SimpleTool.builder().name("propose_x").label("Proposed")
                        .description("p").parameters(ToolSchemas.object(Map.of(), List.of()))
                        .requiredPermission("ai:predict")
                        .handler((args, ctx) -> { ctx.recordProposal(proposalId); return Map.of(); })
                        .build()), new PermissionService(), new ObjectMapper());
        service = new CopilotService(llm, registry, conversations, proposals, properties);
        llm.then(calls(new ToolCall("c1", "propose_x", "{}"))).then(text("Waiting for you to confirm."));

        service.send(conversation.getId(), "do it");

        verify(proposals).attachToMessage(eq(List.of(proposalId)), any(UUID.class));
    }

    @Test
    @DisplayName("keeps the question but saves no answer when the provider fails")
    void providerFailure() {
        llm.thenFail("The provider rejected the API key.");

        assertThatThrownBy(() -> service.send(conversation.getId(), "hello"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("API key")
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE);
        verify(conversations).saveTurn(eq(conversation), eq("hello"), isNull(), isNull(), anyList());
    }

    @Test
    @DisplayName("refuses without a configured provider, before saving anything")
    void notConfigured() {
        llm.configured = false;

        assertThatThrownBy(() -> service.send(conversation.getId(), "hello"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("ADVISOR_API_KEY");
        verify(conversations, never()).saveTurn(any(), anyString(), any(), any(), anyList());
    }

    @Test
    @DisplayName("rejects an over-long message")
    void tooLong() {
        assertThatThrownBy(() -> service.send(conversation.getId(), "x".repeat(4001)))
                .isInstanceOf(ApplicationException.class);
        assertThat(llm.requests).isEmpty();
    }

    @Test
    @DisplayName("someone else's conversation is not found")
    void notMine() {
        UUID other = UUID.randomUUID();
        when(conversations.requireOwned(other)).thenThrow(ResourceNotFoundException.of("Conversation", other));

        assertThatThrownBy(() -> service.send(other, "hi")).isInstanceOf(ResourceNotFoundException.class);
        assertThat(llm.requests).isEmpty();
    }
}
