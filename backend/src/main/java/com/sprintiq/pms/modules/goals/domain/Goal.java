package com.sprintiq.pms.modules.goals.domain;

import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An objective. The sentence a team is trying to make true this quarter.
 *
 * <p>Carries no number of its own — {@code progressPct} is a cached roll-up of its
 * key results, stored so listing a quarter's goals does not aggregate every child on
 * every read, and recomputed whenever one of them moves.</p>
 */
@Entity
@Table(name = "goals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Goal extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "workspace_id")
    private UUID workspaceId;

    /** Null for an organization-wide objective, which is the common case. */
    @Column(name = "project_id")
    private UUID projectId;

    /** Ladders this objective up to a broader one. */
    @Column(name = "parent_goal_id")
    private UUID parentGoalId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * The cycle this belongs to, as the team names it ("2026-Q3", "H2"). Text
     * because those names are what people say out loud; the dates below carry the
     * machine-readable version.
     */
    @Column(name = "period", length = 32)
    private String period;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private GoalStatus status = GoalStatus.ON_TRACK;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 24)
    private GoalLevel level = GoalLevel.TEAM;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Builder.Default
    @Column(name = "progress_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal progressPct = BigDecimal.ZERO;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Recomputes progress and status from the key results.
     *
     * <p>An unweighted mean, deliberately. Weighting invites the argument about
     * whose result matters more to be had at review time, when the honest answer —
     * that the objective is only as done as its least-done part — is already
     * visible in the individual bars.</p>
     *
     * <p>Status is only derived while the goal is open. A goal explicitly marked
     * ACHIEVED or MISSED at the end of a cycle keeps that verdict; recomputing it
     * would let a late number quietly rewrite history.</p>
     */
    public void recalculateFrom(List<KeyResult> keyResults) {
        recalculateFrom(keyResults, LocalDate.now());
    }

    /** Overload taking the date, so the pacing logic is testable without a clock. */
    public void recalculateFrom(List<KeyResult> keyResults, LocalDate today) {
        if (keyResults.isEmpty()) {
            progressPct = BigDecimal.ZERO;
            return;
        }

        BigDecimal total = keyResults.stream()
                .map(KeyResult::progressPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        progressPct = total.divide(BigDecimal.valueOf(keyResults.size()), 2, RoundingMode.HALF_UP);

        if (status.isClosed() || status == GoalStatus.DRAFT) {
            return;
        }

        if (keyResults.stream().allMatch(KeyResult::isAchieved)) {
            status = GoalStatus.ACHIEVED;
            return;
        }

        BigDecimal expected = expectedProgressPct(today);
        if (expected == null) {
            status = progressPct.compareTo(BigDecimal.ZERO) == 0
                    ? GoalStatus.AT_RISK
                    : GoalStatus.ON_TRACK;
            return;
        }

        BigDecimal shortfall = expected.subtract(progressPct);
        if (shortfall.compareTo(BigDecimal.valueOf(35)) > 0) {
            status = GoalStatus.OFF_TRACK;
        } else if (shortfall.compareTo(BigDecimal.valueOf(15)) > 0) {
            status = GoalStatus.AT_RISK;
        } else {
            status = GoalStatus.ON_TRACK;
        }
    }

    /**
     * Where a steady effort would have reached by {@code today}, 0–100.
     *
     * <p>Straight-line across the cycle. Real progress is lumpier than that, which is
     * why the tolerances around it are wide — the line is a reference, not a
     * prediction. Null when the goal has no dates, in which case there is nothing to
     * pace against.</p>
     */
    public BigDecimal expectedProgressPct(LocalDate today) {
        if (startDate == null || targetDate == null) {
            return null;
        }
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, targetDate);
        if (totalDays <= 0) {
            return BigDecimal.valueOf(100);
        }
        long elapsed = java.time.temporal.ChronoUnit.DAYS.between(startDate, today);
        if (elapsed <= 0) return BigDecimal.ZERO;
        if (elapsed >= totalDays) return BigDecimal.valueOf(100);

        return BigDecimal.valueOf(elapsed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);
    }

    /** Past its target date without being achieved. */
    public boolean isOverdue(LocalDate today) {
        return targetDate != null && targetDate.isBefore(today) && status != GoalStatus.ACHIEVED;
    }
}
