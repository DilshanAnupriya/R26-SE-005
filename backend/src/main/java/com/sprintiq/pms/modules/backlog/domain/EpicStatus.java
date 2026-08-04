package com.sprintiq.pms.modules.backlog.domain;

/** Epic lifecycle. Derived from its stories, but stored so it can be set deliberately. */
public enum EpicStatus {

    OPEN,
    IN_PROGRESS,
    DONE,
    CANCELLED;

    public boolean isClosed() {
        return this == DONE || this == CANCELLED;
    }
}
