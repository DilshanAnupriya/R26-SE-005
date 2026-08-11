package com.sprintiq.pms.common.domain;

/**
 * Defect severity — how badly the product is broken, as opposed to {@link Priority},
 * which is how soon the team intends to act. Kept separate because a trivial bug can
 * be urgent and a critical one can be deferred; collapsing them loses that.
 *
 * <p>Only meaningful on items of type {@code BUG}; null elsewhere.</p>
 */
public enum Severity {

    BLOCKER,
    CRITICAL,
    MAJOR,
    MINOR,
    TRIVIAL
}
