package com.sprintiq.pms.modules.roadmap.domain;

/**
 * Where an initiative stands.
 *
 * <p>Deliberately short. A roadmap with eight statuses is one nobody reads, because
 * the distinctions stop meaning anything at a glance — and the whole point of a
 * roadmap is that it is read at a glance.</p>
 */
public enum InitiativeStatus {
    /** Agreed and dated, but nothing has started. */
    PLANNED,
    IN_PROGRESS,
    /** Behind where the calendar says it should be, and still open. */
    AT_RISK,
    SHIPPED,
    CANCELLED;

    /** A decided outcome. Derivation leaves these alone. */
    public boolean isClosed() {
        return this == SHIPPED || this == CANCELLED;
    }
}
