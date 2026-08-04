package com.sprintiq.pms.modules.projects.domain;

import java.util.EnumSet;
import java.util.Set;

/** Project lifecycle. Transitions are validated centrally so no caller can invent a path. */
public enum ProjectStatus {

    PLANNING,
    ACTIVE,
    ON_HOLD,
    COMPLETED,
    ARCHIVED;

    private static final Set<ProjectStatus> TERMINAL = EnumSet.of(COMPLETED, ARCHIVED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Archiving is reachable from anywhere (it is the "put this away" action), and a
     * completed project can be reopened to ACTIVE because teams routinely discover
     * follow-up work after sign-off. Everything else moves forward only.
     */
    public boolean canTransitionTo(ProjectStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case PLANNING -> target == ACTIVE || target == ON_HOLD || target == ARCHIVED;
            case ACTIVE -> target == ON_HOLD || target == COMPLETED || target == ARCHIVED;
            case ON_HOLD -> target == ACTIVE || target == COMPLETED || target == ARCHIVED;
            case COMPLETED -> target == ACTIVE || target == ARCHIVED;
            case ARCHIVED -> target == ACTIVE;
        };
    }
}
