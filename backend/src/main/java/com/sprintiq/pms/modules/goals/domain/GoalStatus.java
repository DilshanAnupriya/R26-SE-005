package com.sprintiq.pms.modules.goals.domain;

/**
 * How an objective is tracking.
 *
 * <p>Deliberately richer than a boolean. The point of an OKR review is the
 * difference between "we will miss this" and "we already missed it" — collapsing
 * both into "not achieved" removes the only distinction that changes what anyone
 * does next.</p>
 */
public enum GoalStatus {
    DRAFT,
    ON_TRACK,
    AT_RISK,
    OFF_TRACK,
    ACHIEVED,
    MISSED;

    public boolean isClosed() {
        return this == ACHIEVED || this == MISSED;
    }
}
