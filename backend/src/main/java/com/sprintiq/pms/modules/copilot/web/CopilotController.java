package com.sprintiq.pms.modules.copilot.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.copilot.application.ConversationService;
import com.sprintiq.pms.modules.copilot.application.CopilotService;
import com.sprintiq.pms.modules.copilot.application.ProposalService;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ConversationDetail;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ConversationSummary;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.CopilotStatusResponse;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ProposalResponse;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.SendMessageRequest;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.SendMessageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The Copilot: saved conversations with an LLM that reads the system and the four
 * models through tools, and proposes changes a person confirms.
 *
 * <p>Requires {@code ai:predict}, the permission every other AI surface uses. What the
 * assistant can then <em>do</em> is narrower: each tool and each proposal checks the
 * permission its own controller would.</p>
 */
@Tag(name = "Copilot", description = "LLM assistant over projects, sprints and the AI models")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/copilot")
@RequiredArgsConstructor
@PreAuthorize("@perm.has('ai:predict')")
public class CopilotController {

    private final CopilotService copilot;
    private final ConversationService conversations;
    private final ProposalService proposals;

    @Operation(summary = "Whether the Copilot is enabled, and if not, why")
    @GetMapping("/status")
    public CopilotStatusResponse status() {
        return copilot.status();
    }

    @Operation(summary = "The current user's conversations, newest first")
    @GetMapping("/conversations")
    public List<ConversationSummary> conversations() {
        return conversations.list();
    }

    @Operation(summary = "Start a conversation")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/conversations")
    public ConversationSummary create() {
        return conversations.create();
    }

    @Operation(summary = "A conversation with its messages and proposals")
    @GetMapping("/conversations/{conversationId}")
    public ConversationDetail conversation(@PathVariable UUID conversationId) {
        return conversations.get(conversationId);
    }

    @Operation(summary = "Delete a conversation and everything in it")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/conversations/{conversationId}")
    public void delete(@PathVariable UUID conversationId) {
        conversations.delete(conversationId);
    }

    @Operation(summary = "Ask the Copilot something",
            description = "Runs one turn: the model may call several tools before it answers. "
                    + "Returns 503 AI_SERVICE_UNAVAILABLE when no provider is configured or the "
                    + "provider fails; the question is still saved in the latter case.")
    @PostMapping("/conversations/{conversationId}/messages")
    public SendMessageResponse send(@PathVariable UUID conversationId,
                                    @Valid @RequestBody SendMessageRequest request) {
        return copilot.send(conversationId, request.content());
    }

    @Operation(summary = "Carry out a proposed change",
            description = "Re-checks ownership, expiry and the permission the change needs, then "
                    + "applies it through the owning module. A refused change comes back FAILED "
                    + "with the reason.")
    @PostMapping("/proposals/{proposalId}/confirm")
    public ProposalResponse confirm(@PathVariable UUID proposalId) {
        return proposals.confirm(proposalId);
    }

    @Operation(summary = "Decline a proposed change")
    @PostMapping("/proposals/{proposalId}/reject")
    public ProposalResponse reject(@PathVariable UUID proposalId) {
        return proposals.reject(proposalId);
    }
}
