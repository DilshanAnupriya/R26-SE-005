package com.sprintiq.pms.modules.sprints.domain;

/**
 * Sprint lifecycle.
 *
 * <p>The transitions are one-way by design. A sprint is a promise the team made at a
 * point in time, and reopening a completed one would let its commitment be edited after
 * the fact — which is exactly the number every velocity chart and every AI model reads
 * as ground truth. A sprint that closed wrongly is corrected by what happens next, not
 * by rewriting what happened.</p>
 */
public enum SprintStatus {

    PLANNED,
    ACTIVE,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean canTransitionTo(SprintStatus target) {
        return switch (this) {
            case PLANNED -> target == ACTIVE || target == CANCELLED;
            case ACTIVE -> target == COMPLETED || target == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    /** Scope may be edited freely before the sprint starts; afterwards changes are tracked. */
    public boolean isScopeMutableWithoutTracking() {
        return this == PLANNED;
    }
}
