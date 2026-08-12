package com.sprintiq.pms.modules.goals.domain;

/** How a key result's number should be read and rendered. */
public enum MetricType {
    NUMBER,
    PERCENT,
    CURRENCY,
    /**
     * Did it happen. Modelled explicitly rather than as a 0-to-1 NUMBER so the UI
     * can render a checkbox instead of a progress bar reading "50% of shipped".
     */
    BOOLEAN
}
