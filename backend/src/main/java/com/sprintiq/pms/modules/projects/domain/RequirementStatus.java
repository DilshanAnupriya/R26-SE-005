package com.sprintiq.pms.modules.projects.domain;

/**
 * Requirement lifecycle. Once a requirement is APPROVED, every subsequent edit bumps
 * {@code change_count} — that counter is the requirement-volatility feature behind the
 * M2 requirement-change risk model, so changes before approval (still being drafted)
 * deliberately do not count against it.
 */
public enum RequirementStatus {

    DRAFT,
    APPROVED,
    IN_PROGRESS,
    IMPLEMENTED,
    VERIFIED,
    REJECTED;

    /** Edits after this point are churn, and are counted as such. */
    public boolean isBaselined() {
        return this != DRAFT;
    }
}
