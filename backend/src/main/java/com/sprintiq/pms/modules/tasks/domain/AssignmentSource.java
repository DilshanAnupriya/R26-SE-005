package com.sprintiq.pms.modules.tasks.domain;

/**
 * Where a task's assignee came from — the M3 counterpart to the story's
 * {@code EstimateSource}.
 *
 * <p>The allocation model ranks candidates; recording whether the team took the top
 * suggestion or picked someone else is what turns the ranking into a feedback loop
 * instead of a suggestion nobody ever measures.</p>
 */
public enum AssignmentSource {

    MANUAL,
    AI_ACCEPTED,
    AI_OVERRIDDEN;

    public boolean isAiInvolved() {
        return this != MANUAL;
    }
}
