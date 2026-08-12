package com.sprintiq.pms.modules.backlog.domain;

/**
 * What kind of backlog item this is.
 *
 * <p>Not cosmetic: the M1 story-point model treats type as a feature (bugs and spikes
 * estimate very differently from features), and velocity reporting counts only work
 * that delivered something — see {@link #countsTowardVelocity}.</p>
 */
public enum StoryType {

    STORY,
    BUG,
    SPIKE,
    CHORE,
    TECH_DEBT;

    /**
     * Spikes are timeboxed investigation. Including their points in velocity inflates
     * the number the team plans the next sprint against, which is the one metric that
     * most needs to stay honest.
     */
    public boolean countsTowardVelocity() {
        return this != SPIKE;
    }

    public boolean isDefect() {
        return this == BUG;
    }
}
