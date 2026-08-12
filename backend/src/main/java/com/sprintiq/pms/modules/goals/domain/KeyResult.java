package com.sprintiq.pms.modules.goals.domain;

import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * A measurable outcome under an objective.
 *
 * <p>The key result carries the number; the goal carries the sentence. That split is
 * what makes an OKR reviewable — "improve onboarding" cannot be argued about
 * productively, "median time-to-first-project under 10 minutes" can.</p>
 */
@Entity
@Table(name = "key_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KeyResult extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", nullable = false, length = 24)
    private MetricType metricType = MetricType.NUMBER;

    @Column(name = "unit", length = 24)
    private String unit;

    /** Where the measure stood when the objective was set. The baseline for progress. */
    @Builder.Default
    @Column(name = "start_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal startValue = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "current_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal currentValue = BigDecimal.ZERO;

    @Column(name = "target_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal targetValue;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private MetricDirection direction = MetricDirection.INCREASE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private GoalStatus status = GoalStatus.ON_TRACK;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Builder.Default
    @Column(name = "position", nullable = false)
    private short position = 0;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Progress from start toward target, 0–100.
     *
     * <p>Measured against the distance actually travelled rather than against the
     * target alone. A result going from 40 to 10 with a target of 5 is 86% of the
     * way there; dividing current by target would report it as 200% and a decreasing
     * metric would look like runaway success the further it fell.</p>
     *
     * <p>Clamped at both ends: negative progress (moving the wrong way) reads as 0
     * rather than as a negative bar, and overachievement caps at 100 so one result
     * cannot mask a failing sibling when the goal averages them.</p>
     */
    public BigDecimal progressPct() {
        BigDecimal span = targetValue.subtract(startValue);
        if (span.compareTo(BigDecimal.ZERO) == 0) {
            // Guarded by a check constraint too; if it ever happens, "done or not"
            // is the only meaningful answer.
            return currentValue.compareTo(targetValue) == 0
                    ? BigDecimal.valueOf(100)
                    : BigDecimal.ZERO;
        }

        BigDecimal travelled = currentValue.subtract(startValue);
        BigDecimal pct = travelled
                .multiply(BigDecimal.valueOf(100))
                .divide(span, 2, RoundingMode.HALF_UP);

        if (pct.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (pct.compareTo(BigDecimal.valueOf(100)) > 0) return BigDecimal.valueOf(100);
        return pct;
    }

    /** Whether the target has been reached, respecting which way is good. */
    public boolean isAchieved() {
        return direction == MetricDirection.INCREASE
                ? currentValue.compareTo(targetValue) >= 0
                : currentValue.compareTo(targetValue) <= 0;
    }

    /**
     * Records a new measurement and re-derives the status.
     *
     * <p>Status is derived rather than set by hand, so a check-in cannot leave a
     * result reading ON_TRACK while its number says otherwise — the commonest way an
     * OKR review becomes fiction.</p>
     *
     * @param expectedPct where a steady effort would have reached by now, 0–100, or
     *                    null when the cycle has no dates to measure against
     */
    public void recordValue(BigDecimal value, BigDecimal expectedPct) {
        currentValue = value;
        status = deriveStatus(expectedPct);
    }

    /** Re-derives the status without changing the value — after a target is edited. */
    public void refreshStatus(BigDecimal expectedPct) {
        status = deriveStatus(expectedPct);
    }

    /**
     * How this result is tracking.
     *
     * <p>Measured against elapsed time, not against progress alone. Every objective
     * is at 0% on the first day of the quarter; calling that OFF_TRACK would mark
     * every new goal as failing and teach everyone to ignore the colour. What matters
     * is whether progress has kept pace with the calendar.</p>
     *
     * <p>The tolerance is asymmetric on purpose: falling a little behind early is
     * normal and recoverable, so AT_RISK starts at 15 points behind pace and
     * OFF_TRACK at 35. Without dates there is nothing to pace against, so it falls
     * back to absolute thresholds and only flags a result that has genuinely not
     * moved.</p>
     */
    private GoalStatus deriveStatus(BigDecimal expectedPct) {
        if (isAchieved()) {
            return GoalStatus.ACHIEVED;
        }
        BigDecimal progress = progressPct();

        if (expectedPct == null) {
            // No cycle dates: a result that has not moved at all is the only thing
            // that can be called off track honestly.
            if (progress.compareTo(BigDecimal.ZERO) == 0) return GoalStatus.AT_RISK;
            return progress.compareTo(BigDecimal.valueOf(40)) >= 0
                    ? GoalStatus.ON_TRACK
                    : GoalStatus.AT_RISK;
        }

        BigDecimal shortfall = expectedPct.subtract(progress);
        if (shortfall.compareTo(BigDecimal.valueOf(35)) > 0) return GoalStatus.OFF_TRACK;
        if (shortfall.compareTo(BigDecimal.valueOf(15)) > 0) return GoalStatus.AT_RISK;
        return GoalStatus.ON_TRACK;
    }
}
