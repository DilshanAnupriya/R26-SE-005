package com.sprintiq.pms.modules.projects.domain;

import java.math.BigDecimal;

/**
 * The kinds of requirement change worth telling apart, and what each one costs the
 * sprint's requirement-change risk.
 *
 * <p>The weights live on the enum rather than in the service because they are the
 * definition of the vocabulary, not a policy applied to it. A reader asking "why did
 * logging that change move the number by eight" should find the answer next to the
 * constant it names, and a reviewer changing a weight should be looking at all six at
 * once — they are only meaningful relative to each other.</p>
 *
 * <p>The magnitudes are ordinal, not measured. Scope added is the worst thing that can
 * happen to a committed sprint and is weighted accordingly; a description edit is
 * mostly noise and is weighted so that a chatty team does not accumulate a crisis. They
 * are an agreed ranking of how much each kind of change disturbs a plan, and the
 * adjustment ledger they feed is explicitly labelled as such in the UI.</p>
 */
public enum RequirementChangeType {

    /**
     * New work admitted after the commitment was frozen. The only change type that is
     * unambiguously bad however it is framed, so it ignores the stated direction.
     */
    SCOPE_ADDED(BigDecimal.valueOf(8), BigDecimal.valueOf(8)),

    /**
     * Work cut. Also direction-fixed, in the other sign: removing scope from a sprint
     * in trouble is the single most effective thing a team can do, and letting somebody
     * log it as an increase would let the ledger be gamed in the wrong direction.
     */
    SCOPE_REMOVED(BigDecimal.valueOf(-5), BigDecimal.valueOf(-5)),

    /** Acceptance criteria expanded or tightened, versus simplified or narrowed. */
    ACCEPTANCE_CRITERIA_CHANGED(BigDecimal.valueOf(5), BigDecimal.valueOf(-3)),

    /** Priority raised mid-sprint, versus de-prioritised. */
    PRIORITY_CHANGED(BigDecimal.valueOf(4), BigDecimal.valueOf(-3)),

    /** Description grew new scope detail, versus resolved an ambiguity. */
    DESCRIPTION_UPDATED(BigDecimal.valueOf(2), BigDecimal.valueOf(-1)),

    /** Anything else the team wants on the record. */
    OTHER(BigDecimal.valueOf(3), BigDecimal.valueOf(-2));

    private final BigDecimal increaseDelta;
    private final BigDecimal decreaseDelta;

    RequirementChangeType(BigDecimal increaseDelta, BigDecimal decreaseDelta) {
        this.increaseDelta = increaseDelta;
        this.decreaseDelta = decreaseDelta;
    }

    /**
     * Whether the direction is the author's to choose. False for the two scope changes,
     * whose sign is inherent; the UI hides the control for those rather than offering a
     * choice it will then ignore.
     */
    public boolean isDirectionFixed() {
        return increaseDelta.equals(decreaseDelta);
    }

    /** The risk points this change contributes. A null direction reads as INCREASE. */
    public BigDecimal riskDelta(ChangeImpactDirection direction) {
        return direction == ChangeImpactDirection.DECREASE ? decreaseDelta : increaseDelta;
    }

    /** The direction actually applied, after the fixed types have overridden the input. */
    public ChangeImpactDirection effectiveDirection(ChangeImpactDirection requested) {
        if (!isDirectionFixed()) {
            return requested == null ? ChangeImpactDirection.INCREASE : requested;
        }
        return increaseDelta.signum() >= 0
                ? ChangeImpactDirection.INCREASE
                : ChangeImpactDirection.DECREASE;
    }

    /** The sentence that appears in the risk adjustment ledger. */
    public String reason(ChangeImpactDirection direction) {
        boolean decrease = direction == ChangeImpactDirection.DECREASE;
        return switch (this) {
            case SCOPE_ADDED -> "Scope added: new work after the commitment was frozen";
            case SCOPE_REMOVED -> "Scope removed: less to deliver than was committed";
            case ACCEPTANCE_CRITERIA_CHANGED -> decrease
                    ? "Acceptance criteria simplified or narrowed"
                    : "Acceptance criteria expanded: rework and misalignment risk";
            case PRIORITY_CHANGED -> decrease
                    ? "Priority lowered: pressure on the sprint reduced"
                    : "Priority raised mid-sprint: planning disruption";
            case DESCRIPTION_UPDATED -> decrease
                    ? "Description clarified: ambiguity resolved"
                    : "Description updated: new scope detail added";
            case OTHER -> decrease
                    ? "Requirement change: reduces delivery risk"
                    : "Requirement change: increases delivery risk";
        };
    }
}
