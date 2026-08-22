package com.sprintiq.pms.modules.ai.domain;

/**
 * How loudly a risk should be presented, ordered from quiet to alarming.
 *
 * <p>Distinct from {@code common.Severity}, which grades how broken a defect is. They
 * read similarly and mean unrelated things — a CRITICAL defect describes the product, a
 * CRITICAL risk describes a forecast — and the two vocabularies are not ordered the
 * same way. Sharing one enum would put {@code TRIVIAL} and {@code BLOCKER} on a sprint
 * risk badge.</p>
 */
public enum RiskSeverity {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean isAtLeast(RiskSeverity other) {
        return ordinal() >= other.ordinal();
    }

    /**
     * Bands a 0–1 risk probability.
     *
     * <p>Lives here rather than in the scoring service because two callers need it and
     * they must agree: the service bands the model's own figure, and the wire DTO bands
     * the same figure after the board's adjustments have been folded in. Two copies
     * would eventually differ by a hundredth and put an amber badge on a red number.</p>
     *
     * <p>The bands are wide on purpose. A classifier that has not been calibrated
     * against outcomes cannot justify finer distinctions, and four labels are as much
     * as a dashboard can usefully colour.</p>
     */
    public static RiskSeverity forScore(java.math.BigDecimal score) {
        if (score == null) {
            return LOW;
        }
        double value = score.doubleValue();
        if (value >= 0.80) return CRITICAL;
        if (value >= 0.60) return HIGH;
        if (value >= 0.35) return MEDIUM;
        return LOW;
    }
}
