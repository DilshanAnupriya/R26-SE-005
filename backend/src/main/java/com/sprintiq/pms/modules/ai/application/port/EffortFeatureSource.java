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

    /** Minutes logged against a sprint's tasks. */
    long sprintEffortMinutes(UUID sprintId);

    /** The share of those minutes logged outside contracted hours. */
    long sprintOvertimeMinutes(UUID sprintId);

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
     */
    record SprintSlippage(long estimatedTaskCount,
                          long tasksNotStartedPastHalf,
                          long tasksOverEstimate,
                          long minutesOverEstimate) {

        /** A sprint with no estimated tasks — nothing that can be behind. */
        public static SprintSlippage none() {
            return new SprintSlippage(0, 0, 0, 0);
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
