package com.sprintiq.pms.modules.tasks.domain;

/**
 * Task lifecycle. Shorter than the story lifecycle on purpose: a task is a unit of
 * execution, and giving it a review-and-test pipeline of its own duplicates the story's
 * without adding information.
 *
 * <p>{@code BLOCKED} is a status here rather than only a flag, because a blocked task is
 * genuinely not being worked on — it should leave the in-progress column and stop
 * counting against WIP limits.</p>
 */
public enum TaskStatus {

    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    BLOCKED,
    DONE,
    CANCELLED;

    public boolean isCompleted() {
        return this == DONE;
    }

    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }

    public boolean isStarted() {
        return this == IN_PROGRESS || this == IN_REVIEW;
    }

    /** Open work, for the workload board and the "N tasks remaining" counters. */
    public boolean isOpen() {
        return !isTerminal();
    }

    public boolean isReopenFrom(TaskStatus previous) {
        return previous.isTerminal() && !this.isTerminal();
    }
}
