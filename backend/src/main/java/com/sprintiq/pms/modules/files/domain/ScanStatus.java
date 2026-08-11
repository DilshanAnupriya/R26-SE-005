package com.sprintiq.pms.modules.files.domain;

/**
 * Malware scan state.
 *
 * <p>{@code PENDING} is the honest default: no scanner runs in this phase, and a file
 * marked {@code CLEAN} by nothing is worse than one marked unscanned, because the UI
 * would present it as verified. Downloads of pending files are allowed but the state is
 * returned to the client, so the badge can say what is actually known.</p>
 */
public enum ScanStatus {

    PENDING,
    CLEAN,
    INFECTED,
    SKIPPED;

    /** Only a positive detection blocks a download. */
    public boolean isDownloadable() {
        return this != INFECTED;
    }
}
