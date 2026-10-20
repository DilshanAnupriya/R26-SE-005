package com.sprintiq.pms.modules.copilot.application;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.copilot.domain.CopilotConversation;
import com.sprintiq.pms.modules.copilot.domain.CopilotConversationRepository;
import com.sprintiq.pms.modules.copilot.domain.CopilotMessage;
import com.sprintiq.pms.modules.copilot.domain.CopilotMessageRepository;
import com.sprintiq.pms.modules.copilot.domain.MessageRole;
import com.sprintiq.pms.modules.copilot.domain.ToolStep;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ConversationDetail;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ConversationSummary;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.MessageResponse;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ProposalResponse;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A user's saved conversations.
 *
 * <p>Every lookup is by id <em>and</em> owner, so another user's conversation reads as
 * not found — not forbidden, which would confirm that it exists.</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final CopilotConversationRepository conversations;
    private final CopilotMessageRepository messages;
    private final ProposalService proposals;

    /** What one turn wrote. {@code assistant} is null when the provider failed. */
    public record SavedTurn(CopilotMessage user, CopilotMessage assistant) {}

    @Transactional(readOnly = true)
    public List<ConversationSummary> list() {
        return conversations.findByUserIdOrderByUpdatedAtDesc(TenantContext.requireUserId()).stream()
                .map(ConversationService::summary).toList();
    }

    @Transactional
    public ConversationSummary create() {
        return summary(conversations.save(CopilotConversation.builder()
                .userId(TenantContext.requireUserId()).build()));
    }

    @Transactional(readOnly = true)
    public ConversationDetail get(UUID conversationId) {
        CopilotConversation conversation = requireOwned(conversationId);
        Map<UUID, List<ProposalResponse>> byMessage = proposals.forConversation(conversationId).stream()
                .filter(p -> p.messageId() != null)
                .collect(Collectors.groupingBy(ProposalResponse::messageId));
        List<MessageResponse> thread = messages.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(m -> toResponse(m, byMessage.getOrDefault(m.getId(), List.of())))
                .toList();
        return new ConversationDetail(conversation.getId(), conversation.getTitle(), thread,
                conversation.getCreatedAt(), conversation.getUpdatedAt());
    }

    @Transactional
    public void delete(UUID conversationId) {
        conversations.delete(requireOwned(conversationId));
    }

    @Transactional(readOnly = true)
    public CopilotConversation requireOwned(UUID conversationId) {
        return conversations.findByIdAndUserId(conversationId, TenantContext.requireUserId())
                .orElseThrow(() -> ResourceNotFoundException.of("Conversation", conversationId));
    }

    /** The last {@code limit} visible turns, oldest first. */
    @Transactional(readOnly = true)
    public List<CopilotMessage> recentTurns(UUID conversationId, int limit) {
        List<CopilotMessage> newestFirst = new ArrayList<>(
                messages.findByConversationIdOrderByCreatedAtDesc(conversationId, PageRequest.of(0, limit)));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /**
     * Saves the question and, when there is one, the answer, in one transaction.
     *
     * <p>The question is kept even when the provider failed, so a retry is one click and
     * the thread shows what was asked rather than silently dropping it.</p>
     */
    @Transactional
    public SavedTurn saveTurn(CopilotConversation conversation, String userText, String assistantText,
                              String model, List<ToolStep> toolSteps) {
        Instant now = Instant.now();
        CopilotMessage user = messages.save(CopilotMessage.builder()
                .conversationId(conversation.getId())
                .role(MessageRole.USER)
                .content(userText)
                .build());
        CopilotMessage assistant = null;
        if (assistantText != null) {
            assistant = messages.save(CopilotMessage.builder()
                    .conversationId(conversation.getId())
                    .role(MessageRole.ASSISTANT)
                    .content(assistantText)
                    .model(model)
                    .toolSteps(new ArrayList<>(toolSteps))
                    .build());
        }

        if (conversation.hasDefaultTitle()) {
            conversation.setTitle(titleFrom(userText));
        }
        // Touched explicitly: a new message must move the thread to the top of the list
        // even when the title did not change and Hibernate sees nothing dirty.
        conversation.setUpdatedAt(now);
        conversations.save(conversation);
        return new SavedTurn(user, assistant);
    }

    static String titleFrom(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        int max = CopilotConversation.TITLE_MAX;
        return flat.length() <= max ? flat : flat.substring(0, max - 1).trim() + "…";
    }

    static ConversationSummary summary(CopilotConversation c) {
        return new ConversationSummary(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt());
    }

    static MessageResponse toResponse(CopilotMessage m, List<ProposalResponse> proposals) {
        return new MessageResponse(m.getId(), m.getRole(), m.getContent(), m.getModel(),
                Objects.requireNonNullElse(m.getToolSteps(), List.of()), proposals, m.getCreatedAt());
    }
}
