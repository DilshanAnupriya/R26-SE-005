package com.sprintiq.pms.modules.users.domain;

/**
 * Declared level. Used as a coarse prior where behavioural history is thin — a new
 * joiner has no delivery record, and starting them at the team average would be a worse
 * guess than starting them at their level's.
 *
 * <p>It is a prior, never an override. Once a person has completed work, the measured
 * metrics carry the ranking; a title does not outrank evidence.</p>
 */
public enum Seniority {

    INTERN,
    JUNIOR,
    MID,
    SENIOR,
    LEAD,
    PRINCIPAL
}
