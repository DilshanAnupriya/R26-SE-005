package com.sprintiq.pms.modules.goals.domain;

/**
 * Where an objective sits. Drives which goals a person is shown by default and
 * which ones may legitimately be a parent of which — an individual goal can ladder
 * up to a team goal, not the other way round.
 */
public enum GoalLevel {
    ORGANIZATION,
    WORKSPACE,
    TEAM,
    INDIVIDUAL;

    /** Lower is broader. Used to reject an inverted ladder. */
    public int rank() {
        return ordinal();
    }

    public boolean canLadderUpTo(GoalLevel parent) {
        return parent.rank() < rank();
    }
}
