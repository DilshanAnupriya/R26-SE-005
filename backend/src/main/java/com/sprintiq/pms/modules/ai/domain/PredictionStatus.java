package com.sprintiq.pms.modules.ai.domain;

/**
 * Outcome of one inference call.
 *
 * <p>Failures are persisted rather than thrown away. A model that times out on every
 * story over 400 characters is a fact about the system that only exists if the timeouts
 * were written down, and the alternative — an empty predictions table and a support
 * ticket saying "the AI panel is sometimes blank" — is much harder to act on.</p>
 */
public enum PredictionStatus {

    SUCCESS,

    /** The upstream model answered, but outside its latency budget or with low confidence. */
    DEGRADED,

    FAILED,

    TIMEOUT;

    public boolean isUsable() {
        return this == SUCCESS || this == DEGRADED;
    }
}
