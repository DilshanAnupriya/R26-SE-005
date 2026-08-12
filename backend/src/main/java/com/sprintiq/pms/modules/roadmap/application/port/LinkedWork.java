package com.sprintiq.pms.modules.roadmap.application.port;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What one linked epic or story looks like from the roadmap's point of view.
 *
 * <p>Carries both counts and points because teams estimate inconsistently, and a
 * roadmap that only understood points would report an unestimated epic as zero work
 * — indistinguishable from an epic nobody has started.</p>
 *
 * @param itemKey  the human-readable key (PAY-14), for display
 * @param title    the item's title, for display
 * @param exists   false when the link points at something that has since been deleted
 */
public record LinkedWork(
        String itemKey,
        String title,
        boolean exists,
        int totalItems,
        int completedItems,
        BigDecimal totalPoints,
        BigDecimal completedPoints) {

    /** A link whose target is gone. Contributes nothing rather than crashing the page. */
    public static LinkedWork missing() {
        return new LinkedWork(null, null, false, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * How far through this piece of work is, 0–100.
     *
     * <p>Points when the team estimated, item counts when they did not. Preferring
     * points matters: five one-point chores and one thirteen-point story are not half
     * done when the chores are finished, and a count says they are.</p>
     */
    public BigDecimal progressPct() {
        if (totalPoints != null && totalPoints.compareTo(BigDecimal.ZERO) > 0) {
            return completedPoints
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalPoints, 2, RoundingMode.HALF_UP)
                    .min(BigDecimal.valueOf(100));
        }
        if (totalItems > 0) {
            return BigDecimal.valueOf(completedItems)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalItems), 2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }
}
