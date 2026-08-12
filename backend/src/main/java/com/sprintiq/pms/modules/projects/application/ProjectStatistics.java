package com.sprintiq.pms.modules.projects.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Roll-up shown on a project's overview screen, assembled from every delivery module.
 *
 * <p>Each contributor fills only the fields it owns — backlog knows about epics,
 * stories and points, tasks knows about tasks and blockers, sprints knows which sprint
 * is running — and {@link #merge} folds the partials together. Additive fields sum;
 * the active-sprint fields are single-valued and the first non-null wins, which is
 * safe because only one module ever sets them.</p>
 */
public record ProjectStatistics(
        long epicCount,
        long storyCount,
        long completedStoryCount,
        long taskCount,
        long openTaskCount,
        long blockedCount,
        BigDecimal totalStoryPoints,
        BigDecimal completedStoryPoints,
        UUID activeSprintId,
        String activeSprintName) {

    public static ProjectStatistics empty() {
        return new ProjectStatistics(0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
    }

    public ProjectStatistics merge(ProjectStatistics other) {
        if (other == null) {
            return this;
        }
        return new ProjectStatistics(
                epicCount + other.epicCount,
                storyCount + other.storyCount,
                completedStoryCount + other.completedStoryCount,
                taskCount + other.taskCount,
                openTaskCount + other.openTaskCount,
                blockedCount + other.blockedCount,
                nullSafe(totalStoryPoints).add(nullSafe(other.totalStoryPoints)),
                nullSafe(completedStoryPoints).add(nullSafe(other.completedStoryPoints)),
                activeSprintId != null ? activeSprintId : other.activeSprintId,
                activeSprintName != null ? activeSprintName : other.activeSprintName);
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Share of stories finished, 0–100. Used as the project's progress percentage. */
    public BigDecimal completionPct() {
        if (storyCount == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(completedStoryCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(storyCount), 2, java.math.RoundingMode.HALF_UP);
    }
}
