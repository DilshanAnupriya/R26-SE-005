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
}
