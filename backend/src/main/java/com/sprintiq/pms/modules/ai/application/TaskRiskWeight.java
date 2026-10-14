package com.sprintiq.pms.modules.ai.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much one task's board event is worth, by the size of the task.
 *
 * <p>One formula for every per-task charge — blocking, sitting unstarted past half its
 * estimate, going undiscussed past it — so that the same card is the same size to every
 * rule. Two copies drift: the board would call a task big for one penalty and small for
 * another, and nobody reading the ledger could tell why.</p>
 *
 * <p>Task size is measured against a one-day baseline and clamped to 0.5–2.0. Clamped
 * rather than raw so a single fortnight-long task cannot swamp the sprint, and a
 * fifteen-minute one is not treated as free. An unestimated task weighs 1.0 — the absence
 * of an estimate is not evidence that the task is small.</p>
 */
public final class TaskRiskWeight {

    private static final BigDecimal MINUTES_PER_DAY = BigDecimal.valueOf(8 * 60);
    private static final BigDecimal MIN = BigDecimal.valueOf(0.5);
    private static final BigDecimal MAX = BigDecimal.valueOf(2.0);

    private TaskRiskWeight() {
    }

    /** The task's size against a one-day task. */
    public static BigDecimal of(Integer estimateMinutes) {
        if (estimateMinutes == null || estimateMinutes <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal raw = BigDecimal.valueOf(estimateMinutes)
                .divide(MINUTES_PER_DAY, 4, RoundingMode.HALF_UP);
        return raw.max(MIN).min(MAX);
    }

    /**
     * {@code base} points for a one-day task, scaled by size and held under {@code cap}.
     */
    public static BigDecimal charge(Integer estimateMinutes, BigDecimal base, BigDecimal cap) {
        return of(estimateMinutes).multiply(base).min(cap).setScale(2, RoundingMode.HALF_UP);
    }
}
