package com.sprintiq.pms.modules.ai.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Whether a cost forecast is far enough over budget to count as sprint risk.
 *
 * <p>The whole subtlety is the margin. The cost models predict money to within about a
 * quarter either way, so a forecast 20% over budget is not evidence of an overrun — it
 * is a number that would look the same if the sprint were exactly on plan. Only the part
 * of the variance <em>beyond</em> that error is treated as signal.</p>
 *
 * <p>Kept out of the service so the rule can be exercised directly. The decision has four
 * distinct outcomes — no budget, no forecast, inside the margin, and charged — and three
 * of them are the ones a service test would be least likely to cover.</p>
 */
public record CostOverrun(BigDecimal forecastAmount,
                          BigDecimal budgetAmount,
                          BigDecimal variancePct,
                          double marginPct,
                          Outcome outcome,
                          BigDecimal delta) {

    public enum Outcome {
        /** The sprint has no budget, so there is nothing to be over. */
        NO_BUDGET,
        /** Nothing has been forecast yet. */
        NO_FORECAST,
        /** Under budget, or over by less than the model's own error. */
        WITHIN_MARGIN,
        /** Over by more than the model can explain away — charged as risk. */
        CHARGED
    }

    public boolean isCharged() {
        return outcome == Outcome.CHARGED;
    }

    /**
     * Judges one forecast.
     *
     * @param variancePct forecast against budget, as a percentage. Null when either is
     *                    missing — which is not the same as zero, and must not be read as
     *                    "exactly on budget"
     * @param marginPct   the deployed model's typical error
     */
    public static CostOverrun evaluate(BigDecimal forecastAmount, BigDecimal budgetAmount,
                                       BigDecimal variancePct, double marginPct,
                                       double pctPerRiskPoint, double minDelta, double maxDelta) {
        if (forecastAmount == null) {
            return new CostOverrun(null, budgetAmount, null, marginPct,
                    Outcome.NO_FORECAST, BigDecimal.ZERO);
        }
        if (budgetAmount == null || budgetAmount.signum() <= 0 || variancePct == null) {
            // No budget is a gap in the plan, not a clean bill. Reported as its own
            // outcome so the UI can say "nothing to compare against" rather than
            // rendering a reassuring zero.
            return new CostOverrun(forecastAmount, budgetAmount, variancePct, marginPct,
                    Outcome.NO_BUDGET, BigDecimal.ZERO);
        }

        double beyond = variancePct.doubleValue() - marginPct;
        if (beyond <= 0) {
            return new CostOverrun(forecastAmount, budgetAmount, variancePct, marginPct,
                    Outcome.WITHIN_MARGIN, BigDecimal.ZERO);
        }

        double points = Math.min(maxDelta, Math.max(minDelta, beyond / pctPerRiskPoint));
        return new CostOverrun(forecastAmount, budgetAmount, variancePct, marginPct,
                Outcome.CHARGED, BigDecimal.valueOf(points).setScale(1, RoundingMode.HALF_UP));
    }

    /** How far past the margin, for the sentence that explains the charge. */
    public BigDecimal beyondMarginPct() {
        if (variancePct == null) {
            return BigDecimal.ZERO;
        }
        double beyond = variancePct.doubleValue() - marginPct;
        return beyond <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(beyond).setScale(1, RoundingMode.HALF_UP);
    }
}
