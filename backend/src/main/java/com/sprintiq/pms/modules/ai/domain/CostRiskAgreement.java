package com.sprintiq.pms.modules.ai.domain;

/**
 * Whether the two models that care about going over budget agree.
 *
 * <p>They reach the same question by different routes and never see each other's work.
 * {@code M2_OVER_BUDGET} reads how the team is behaving — velocity, carry-over, rework,
 * fatigue — and never sees money. The cost model reads effort hours against a rate and
 * never sees behaviour. So their agreement is genuine corroboration, and their
 * disagreement is the more useful signal: it says which of the two things went wrong.</p>
 */
public enum CostRiskAgreement {

    /**
     * Both say trouble. The strongest reading available here — two models with no shared
     * inputs reaching the same conclusion.
     */
    BOTH_HIGH,

    /** Both say fine. Same corroboration, in the reassuring direction. */
    BOTH_LOW,

    /**
     * The money is over but the team looks normal.
     *
     * <p>Usually means the plan was wrong rather than the execution: nobody is blocked,
     * nothing is being reworked, the sprint is simply bigger than it was priced at.
     * Chasing team behaviour will not close this gap.</p>
     */
    COST_ONLY,

    /**
     * The team is struggling but the money is fine.
     *
     * <p>The reverse: work that is going badly but happens to be cheap. A people or
     * process problem, and re-budgeting would not touch it.</p>
     */
    RISK_ONLY,

    /** One side has nothing to say — no budget, or no assessment yet. */
    UNKNOWN;

    public boolean isDisagreement() {
        return this == COST_ONLY || this == RISK_ONLY;
    }

    /**
     * Reads the two verdicts against each other.
     *
     * @param riskSeverity the behavioural model's band, null when not assessed
     * @param costCharged  whether the cost overrun was large enough to count — reusing
     *                     the same threshold the ledger charges on, so the panel and the
     *                     score cannot disagree about whether the sprint is over budget
     * @param costComparable whether there was a budget to compare against at all
     */
    public static CostRiskAgreement of(RiskSeverity riskSeverity, boolean costCharged,
                                       boolean costComparable) {
        if (riskSeverity == null || !costComparable) {
            return UNKNOWN;
        }
        boolean riskHigh = riskSeverity.isAtLeast(RiskSeverity.HIGH);
        if (riskHigh && costCharged) {
            return BOTH_HIGH;
        }
        if (costCharged) {
            return COST_ONLY;
        }
        return riskHigh ? RISK_ONLY : BOTH_LOW;
    }
}
