package com.sprintiq.pms.modules.copilot.web.dto;

import com.sprintiq.pms.modules.copilot.domain.MessageRole;
import com.sprintiq.pms.modules.copilot.domain.ProposalStatus;
import com.sprintiq.pms.modules.copilot.domain.ProposalType;
import com.sprintiq.pms.modules.copilot.domain.ToolStep;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CopilotDtos {

    private CopilotDtos() {}

    /** Longest message a user may send. Enough for a pasted paragraph, not a pasted backlog. */
    public static final int MAX_MESSAGE_CHARS = 4000;

    @Schema(name = "CopilotStatus")
    public record CopilotStatusResponse(boolean enabled, String model, String reason) {}

    @Schema(name = "CopilotConversationSummary")
    public record ConversationSummary(UUID id, String title, Instant createdAt, Instant updatedAt) {}

    @Schema(name = "CopilotProposal", description = "A change the assistant suggested. Nothing "
            + "happens until the owning user confirms it.")
    public record ProposalResponse(UUID id,
                                   UUID conversationId,
                                   UUID messageId,
                                   ProposalType type,
                                   @Schema(description = "Written by the server from the payload, not by the model")
                                   String summary,
                                   @Schema(description = "The assistant's stated reason. Model-written.")
                                   String reason,
                                   ProposalStatus status,
                                   Instant expiresAt,
                                   Instant resolvedAt,
                                   String resultMessage,
                                   Map<String, Object> payload) {}

    @Schema(name = "CopilotMessage")
    public record MessageResponse(UUID id,
                                  MessageRole role,
                                  String content,
                                  String model,
                                  List<ToolStep> toolSteps,
                                  List<ProposalResponse> proposals,
                                  Instant createdAt) {}

    @Schema(name = "CopilotConversation")
    public record ConversationDetail(UUID id,
                                     String title,
                                     List<MessageResponse> messages,
                                     Instant createdAt,
                                     Instant updatedAt) {}

    @Schema(name = "CopilotSendMessageRequest")
    public record SendMessageRequest(@NotBlank @Size(max = MAX_MESSAGE_CHARS) String content) {}

    @Schema(name = "CopilotSendMessageResponse")
    public record SendMessageResponse(ConversationSummary conversation,
                                      MessageResponse userMessage,
                                      MessageResponse assistantMessage) {}
}
