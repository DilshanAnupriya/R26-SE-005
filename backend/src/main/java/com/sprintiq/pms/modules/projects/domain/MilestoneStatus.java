package com.sprintiq.pms.modules.projects.domain;

/** Milestone lifecycle. MISSED is set when the due date passes without completion. */
public enum MilestoneStatus {

    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    MISSED
}
