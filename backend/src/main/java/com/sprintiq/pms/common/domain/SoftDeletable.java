package com.sprintiq.pms.common.domain;

import java.time.Instant;

/** Entities that support the 30-day recycle-bin recovery window. */
public interface SoftDeletable {

    Instant getDeletedAt();

    void setDeletedAt(Instant deletedAt);

    default boolean isDeleted() {
        return getDeletedAt() != null;
    }
}
