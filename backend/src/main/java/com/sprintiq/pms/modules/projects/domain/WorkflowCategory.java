package com.sprintiq.pms.modules.projects.domain;

/**
 * Buckets a project's custom workflow states into the three phases every report needs.
 *
 * <p>Teams rename and reorder their columns freely ("In Review", "Ready for QA",
 * "Staging"), but burndown, cycle time and the cumulative flow diagram all have to know
 * which columns mean not-started, in-flight and finished. The category is that mapping,
 * so analytics never has to guess from a column's name.</p>
 */
public enum WorkflowCategory {

    TODO,
    IN_PROGRESS,
    DONE,
    CANCELLED;

    /** Work in DONE counts as completed for velocity; CANCELLED is removed scope, not delivery. */
    public boolean isCompleted() {
        return this == DONE;
    }

    public boolean isActive() {
        return this == IN_PROGRESS;
    }
}
