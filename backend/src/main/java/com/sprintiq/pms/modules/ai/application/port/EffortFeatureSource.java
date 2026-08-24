package com.sprintiq.pms.modules.ai.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recorded effort — the only non-estimated quantity any of these models gets to see.
 *
 * <p>Mid-sprint cost and over-budget risk both hinge on it. Everything else in the
 * feature set is a plan or a guess; logged minutes are what actually happened, which is
 * why the mid-sprint forecast can carry a much narrower error margin than the initial
 * one.</p>
 */
public interface EffortFeatureSource {

    record UserEffort(UUID userId, long minutes, long overtimeMinutes) {}

    /** Minutes logged against a sprint's tasks. */
    long sprintEffortMinutes(UUID sprintId);

    /** The share of those minutes logged outside contracted hours. */
    long sprintOvertimeMinutes(UUID sprintId);

    /** Logged sprint effort grouped by author so actual cost can use each person's rate. */
    List<UserEffort> sprintEffortByUser(UUID sprintId);

    /** Remaining estimated minutes on open tasks in the sprint. */
    long sprintRemainingEstimateMinutes(UUID sprintId);

    /** Original estimated minutes across all non-deleted sprint tasks. */
    long sprintPlannedEstimateMinutes(UUID sprintId);

    /** Tasks currently attached to the sprint. */
    long sprintTaskCount(UUID sprintId);

    /** Required skill names across tasks in the sprint. */
    List<String> sprintRequiredSkills(UUID sprintId);

    /** Open assigned work in the project, used as the concurrency cost signal. */
    long concurrentAssignments(UUID projectId);

    /** Current open-work load per user on a project, as a percentage of one sprint. */
    Map<UUID, BigDecimal> loadByUser(UUID projectId);

    /**
     * Tasks that have already fallen behind their own estimate.
     *
     * <p>The over-budget model's other inputs are all plan-versus-plan: committed points
     * against velocity, planned hours against capacity. Those are known on day one and
     * do not move, so a sprint that was planned sensibly and is going badly scores the
     * same on day nine as it did at planning. These two counts are the mid-sprint
     * correction — they are the first thing that is true only because time has passed.</p>
     *
     * @param tasksNotStartedPastHalf estimated tasks still untouched past the halfway
     *                                point of their own estimate. Not the same as "late":
     *                                a task that has not started cannot finish on time,
     *                                and it is knowable well before the deadline is.
     * @param tasksOverEstimate       tasks that have burned their full estimate without
     *                                reaching Done
     * @param minutesOverEstimate     by how much, in total — one task 400% over and four
     *                                tasks slightly over are the same count and different
     *                                problems
     * @param taskReopens             every reopen across the sprint's tasks, summed rather
     *                                than counted by task. Rework is unplanned work against
     *                                an estimate that was already spent, so it belongs to
     *                                the over-budget picture as much as to the
     *                                collaboration one — and the sprint metric next to it
     *                                sums story reopens only, so without this a team that
     *                                tracks work as tasks reports no rework at all
     */
    record SprintSlippage(long estimatedTaskCount,
                          long tasksNotStartedPastHalf,
                          long tasksOverEstimate,
                          long minutesOverEstimate,
                          long taskReopens) {

        /** A sprint with no tasks — nothing that can be behind, and nothing reworked. */
        public static SprintSlippage none() {
            return new SprintSlippage(0, 0, 0, 0, 0);
        }

        /** Slipping tasks as a share of the estimated ones, 0..1. */
        public BigDecimal slippageRate() {
            if (estimatedTaskCount <= 0) {
                return BigDecimal.ZERO;
            }
            // Counted rather than summed: a task can be both untouched past half-time
            // and past its full estimate, and it is one late task either way.
            long slipping = Math.min(tasksNotStartedPastHalf + tasksOverEstimate, estimatedTaskCount);
            return BigDecimal.valueOf(slipping)
                    .divide(BigDecimal.valueOf(estimatedTaskCount), 4, java.math.RoundingMode.HALF_UP);
        }
    }

    SprintSlippage sprintSlippage(UUID sprintId);
}
