package com.sprintiq.pms.modules.backlog.domain;

/**
 * Story lifecycle.
 *
 * <p>Runs alongside the project's custom {@code WorkflowState} rather than replacing
 * it: the workflow state is what the team sees on their board and renames freely,
 * while this enum is the fixed vocabulary that reports, the API and the AI feature
 * assemblers can rely on. Moving a story between board columns updates both.</p>
 */
public enum StoryStatus {

    BACKLOG,
    READY,
    IN_PROGRESS,
    IN_REVIEW,
    TESTING,
    DONE,
    CANCELLED;

    public boolean isCompleted() {
        return this == DONE;
    }

    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }

    /** Work has started — the point after which moving a story between sprints is spillover. */
    public boolean isStarted() {
        return this == IN_PROGRESS || this == IN_REVIEW || this == TESTING;
    }

    /**
     * Moving out of a terminal status is a reopen, which is tracked per item and feeds
     * the reopen-rate feature of the M3 developer model.
     */
    public boolean isReopenFrom(StoryStatus previous) {
        return previous.isTerminal() && !this.isTerminal();
    }
}
