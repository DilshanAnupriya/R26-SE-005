package com.sprintiq.pms.modules.ai.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Sprint-level facts feeding the velocity predictor (M1), both risk classifiers (M2)
 * and both cost forecasters (M4).
 *
 * <p>One port rather than three because all three models are asking about the same
 * object and would otherwise assemble overlapping halves of it — three code paths that
 * drift until "committed points" means something subtly different in the risk widget
 * than in the cost widget, on the same screen.</p>
 */
public interface SprintFeatureSource {

    /**
     * @param historicalVelocities completed-point totals of previous sprints, oldest
     *                             first. The sequence, not just the mean: a team going
     *                             12, 18, 24 and a team going 24, 18, 12 have the same
     *                             average and opposite futures.
     */
    record SprintFeatures(UUID sprintId,
                          UUID projectId,
                          String name,
                          /** The stated sprint goal, or null — a strong M2 signal on its own. */
                          String goal,
                          int sequenceNo,
                          String status,
                          LocalDate startDate,
                          LocalDate endDate,
                          Integer durationDays,
                          Integer daysElapsed,
                          Integer daysRemaining,
                          Short teamSize,
                          BigDecimal capacityPoints,
                          BigDecimal capacityHours,
                          BigDecimal committedPoints,
                          BigDecimal completedPoints,
                          BigDecimal addedPoints,
                          BigDecimal removedPoints,
                          BigDecimal absenceDays,
                          BigDecimal overtimeHours,
                          int totalEffortMinutes,
                          BigDecimal plannedCostAmount,
                          BigDecimal budgetAmount,
                          long storyCount,
                          long completedStoryCount,
                          long blockedCount,
                          long defectCount,
                          long reopenedCount,
                          long unestimatedCount,
                          long contributorCount,
                          BigDecimal rollingVelocity,
                          List<BigDecimal> historicalVelocities) {

        /** How far through the sprint we are, 0..1. Zero for sprints that have not started. */
        public BigDecimal elapsedFraction() {
            if (durationDays == null || durationDays <= 0 || daysElapsed == null) {
                return BigDecimal.ZERO;
            }
            BigDecimal fraction = BigDecimal.valueOf(daysElapsed)
                    .divide(BigDecimal.valueOf(durationDays), 4, java.math.RoundingMode.HALF_UP);
            return fraction.min(BigDecimal.ONE).max(BigDecimal.ZERO);
        }

        /** Commitment as a multiple of what the team usually delivers. */
        public BigDecimal commitmentRatio() {
            if (rollingVelocity == null || rollingVelocity.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ONE;
            }
            return committedPoints.divide(rollingVelocity, 4, java.math.RoundingMode.HALF_UP);
        }

        /** Scope churn as a fraction of the frozen commitment. */
        public BigDecimal scopeVolatility() {
            if (committedPoints == null || committedPoints.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            return addedPoints.add(removedPoints)
                    .divide(committedPoints, 4, java.math.RoundingMode.HALF_UP);
        }
    }

    SprintFeatures featuresFor(UUID sprintId);

    /** Sprint ids of a project, newest first — used to build project-level insights. */
    List<UUID> recentSprintIds(UUID projectId, int limit);
}
