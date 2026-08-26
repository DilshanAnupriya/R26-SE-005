package com.sprintiq.pms.modules.backlog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much of a story's estimated time its tasks have already claimed.
 *
 * <p>Story points are converted to minutes at the project's own rate, so a 5-point
 * story on a project where a point is an hour has 300 minutes to spend. Each task
 * created under it spends some. The point of the object is the remainder: a planner
 * about to add a task should see what is left before typing a number, not discover
 * afterwards that the breakdown outgrew the estimate.</p>
 *
 * <p>An unpointed story has no budget rather than a budget of zero. Those are
 * different: zero says "no time available, everything is an overrun", and a story
 * nobody has sized yet should not make the sprint look like it is in trouble.</p>
 *
 * @param storyPoints      the story's estimate, null when unsized
 * @param hoursPerPoint    the project's conversion rate
 * @param budgetMinutes    points × rate, null when unsized
 * @param consumedMinutes  the tasks' estimates, summed
 * @param taskCount        tasks under the story
 * @param unestimatedTasks how many carry no estimate — they consume nothing measurable,
 *                         which is worth saying out loud rather than reading as slack
 */
public record StoryTaskBudget(BigDecimal storyPoints,
                              BigDecimal hoursPerPoint,
                              Long budgetMinutes,
                              long consumedMinutes,
                              long taskCount,
                              long unestimatedTasks) {

    /** Minutes still unspent. Negative once the breakdown has outgrown the story. */
    public Long remainingMinutes() {
        return budgetMinutes == null ? null : budgetMinutes - consumedMinutes;
    }

    /** Minutes spent beyond the budget, or zero. Never negative — that is the remainder. */
    public long overrunMinutes() {
        if (budgetMinutes == null) {
            return 0;
        }
        return Math.max(0, consumedMinutes - budgetMinutes);
    }

    public boolean isOverBudget() {
        return overrunMinutes() > 0;
    }

    /** Whether a budget can be computed at all. */
    public boolean isSized() {
        return budgetMinutes != null && budgetMinutes > 0;
    }

    /**
     * Consumed as a share of budget: 1.0 is exactly spent, 1.5 is fifty percent over.
     *
     * <p>Null when unsized, so a caller cannot accidentally treat "no estimate" as a
     * ratio of zero and conclude the story is comfortably under.</p>
     */
    public BigDecimal consumedRatio() {
        if (!isSized()) {
            return null;
        }
        return BigDecimal.valueOf(consumedMinutes)
                .divide(BigDecimal.valueOf(budgetMinutes), 3, RoundingMode.HALF_UP);
    }

    /**
     * Whether adding {@code minutes} more would exceed what is left.
     *
     * <p>Answered here rather than in the UI so the warning a planner sees and the risk
     * the sprint is charged come from one rule. Two implementations of "is this over"
     * would eventually disagree, and the one people would trust is the one that lets
     * them save.</p>
     */
    public boolean wouldExceed(Integer minutes) {
        if (!isSized() || minutes == null || minutes <= 0) {
            return false;
        }
        return consumedMinutes + minutes > budgetMinutes;
    }

    public static StoryTaskBudget unsized(BigDecimal hoursPerPoint, long consumedMinutes,
                                          long taskCount, long unestimatedTasks) {
        return new StoryTaskBudget(null, hoursPerPoint, null, consumedMinutes,
                taskCount, unestimatedTasks);
    }

    /** Builds a budget from a story's points and the project's rate. */
    public static StoryTaskBudget of(BigDecimal storyPoints, BigDecimal hoursPerPoint,
                                     long consumedMinutes, long taskCount,
                                     long unestimatedTasks) {
        if (storyPoints == null || storyPoints.signum() <= 0
                || hoursPerPoint == null || hoursPerPoint.signum() <= 0) {
            return unsized(hoursPerPoint, consumedMinutes, taskCount, unestimatedTasks);
        }
        long budget = storyPoints.multiply(hoursPerPoint)
                .multiply(BigDecimal.valueOf(60))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        return new StoryTaskBudget(storyPoints, hoursPerPoint, budget, consumedMinutes,
                taskCount, unestimatedTasks);
    }
}
