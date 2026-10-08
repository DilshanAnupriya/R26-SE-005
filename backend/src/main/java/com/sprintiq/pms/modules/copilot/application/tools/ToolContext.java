package com.sprintiq.pms.modules.copilot.application.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * What a tool knows about the turn it runs in, and where it records proposals it made.
 *
 * <p>Proposals are collected here rather than parsed back out of tool output, so the
 * turn can attach them to its answer without trusting anything the model repeated.</p>
 */
public final class ToolContext {

    private final UUID conversationId;
    private final UUID userId;
    private final List<UUID> proposalIds = new ArrayList<>();

    public ToolContext(UUID conversationId, UUID userId) {
        this.conversationId = conversationId;
        this.userId = userId;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public UUID userId() {
        return userId;
    }

    public void recordProposal(UUID proposalId) {
        proposalIds.add(proposalId);
    }

    public List<UUID> proposalIds() {
        return Collections.unmodifiableList(proposalIds);
    }
}
