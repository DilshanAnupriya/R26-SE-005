package com.sprintiq.pms.modules.projects.application;

import java.util.UUID;

/**
 * The projects module's published view of a project, for the delivery modules that
 * hang work off one.
 *
 * <p>Backlog, sprints and tasks all need to check that a project exists, is not
 * archived, and to read its key and cadence. Handing them the {@code Project} entity
 * would put another module's persistence model on their compile path and let them
 * mutate it; this record carries only what they legitimately need, and carries the
 * status as text so the enum stays owned by this module.</p>
 */
public record ProjectRef(
        UUID id,
        UUID workspaceId,
        String projectKey,
        String name,
        String status,
        short defaultSprintDays,
        String currency,
        /** Hours one story point is worth, for sizing a story's task budget. */
        java.math.BigDecimal hoursPerPoint) {

    /** Archived and completed projects reject new work items. */
    public boolean acceptsNewWork() {
        return !"ARCHIVED".equals(status) && !"COMPLETED".equals(status);
    }
}
