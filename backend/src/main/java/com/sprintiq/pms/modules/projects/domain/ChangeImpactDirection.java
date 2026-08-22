package com.sprintiq.pms.modules.projects.domain;

/**
 * Whether a requirement change made delivery harder or easier.
 *
 * <p>Needed because the change type alone does not say. Tightening acceptance criteria
 * and simplifying them are both {@code ACCEPTANCE_CRITERIA_CHANGED}, and treating every
 * logged change as pressure would punish a team for the one thing that actually reduces
 * requirement risk — cutting ambiguity out of a story mid-sprint.</p>
 */
public enum ChangeImpactDirection {

    /** The change adds work, ambiguity or disruption. */
    INCREASE,

    /** The change removes work or resolves ambiguity. */
    DECREASE
}
