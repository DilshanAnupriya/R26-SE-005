package com.sprintiq.pms.modules.backlog.domain;

/**
 * Where a story's point estimate came from.
 *
 * <p>This is the feedback loop for the M1 model, not bookkeeping. An estimate the team
 * accepted unchanged and one they overrode are very different training signals, and the
 * difference is only recoverable if it is recorded at the moment the human decided —
 * afterwards, both look like a number in a column.</p>
 */
public enum EstimateSource {

    /** No estimate yet. Distinct from MANUAL zero, which is a deliberate "this is free". */
    UNESTIMATED,

    /** A person typed the number, with no AI suggestion involved. */
    MANUAL,

    /** The AI suggestion was accepted as-is. */
    AI_ACCEPTED,

    /** An AI suggestion existed and a person changed it — the highest-value signal. */
    AI_OVERRIDDEN;

    public boolean isAiInvolved() {
        return this == AI_ACCEPTED || this == AI_OVERRIDDEN;
    }
}
