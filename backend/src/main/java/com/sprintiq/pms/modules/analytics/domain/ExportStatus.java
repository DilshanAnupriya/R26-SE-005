package com.sprintiq.pms.modules.analytics.domain;

/**
 * Where an export has got to.
 *
 * <p>{@code EXPIRED} is distinct from {@code FAILED} on purpose. A job that failed
 * should be retried; one that expired succeeded and was simply not collected in time,
 * and telling a user their export "failed" when it worked is how support tickets get
 * opened for problems that do not exist.</p>
 */
public enum ExportStatus {

    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    EXPIRED;

    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING;
    }

    public boolean isDownloadable() {
        return this == COMPLETED;
    }
}
