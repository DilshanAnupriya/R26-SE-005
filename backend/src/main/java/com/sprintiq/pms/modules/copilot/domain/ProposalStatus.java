package com.sprintiq.pms.modules.copilot.domain;

public enum ProposalStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    /** Confirmed, but the underlying service refused — the reason is kept. */
    FAILED,
    /** Left unanswered past its expiry; the data it was based on may have moved. */
    EXPIRED
}
