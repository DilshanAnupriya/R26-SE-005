package com.sprintiq.pms.modules.sprints.domain;

import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A timeboxed iteration.
 *
 * <p>The point fields are the product's most load-bearing numbers: velocity, burndown,
 * the M1 velocity model and the M2 over-budget model all read them. {@code committedPoints}
 * freezes when the sprint starts and never moves again — work added afterwards lands in
 * {@code addedPoints} and work pulled lands in {@code removedPoints}. Letting the
 * commitment drift with scope would make every sprint look perfectly planned in
 * hindsight, which destroys the signal those models exist to find.</p>
 *
 * <p>Cost fields are planning forecasts only. Nothing in this system bills (A8).</p>
 */
@Entity
@Table(name = "sprints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sprint extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Monotonic per project — "Sprint 7" keeps its number even if earlier ones were cancelled. */
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "goal", columnDefinition = "text")
    private String goal;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SprintStatus status = SprintStatus.PLANNED;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "duration_days")
    private Short durationDays;

    @Column(name = "team_size")
    private Short teamSize;

    @Column(name = "capacity_points", precision = 8, scale = 2)
    private BigDecimal capacityPoints;

    @Column(name = "capacity_hours", precision = 8, scale = 2)
    private BigDecimal capacityHours;

    /** Frozen at start. The promise the sprint is measured against. */
    @Builder.Default
    @Column(name = "committed_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal committedPoints = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "completed_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal completedPoints = BigDecimal.ZERO;

    /** Scope that arrived after the sprint started — the M2 scope-creep feature. */
    @Builder.Default
    @Column(name = "added_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal addedPoints = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "removed_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal removedPoints = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_effort_minutes", nullable = false)
    private int totalEffortMinutes = 0;

    @Builder.Default
    @Column(name = "absence_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal absenceDays = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "overtime_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal overtimeHours = BigDecimal.ZERO;

    @Column(name = "planned_cost_amount", precision = 14, scale = 2)
    private BigDecimal plannedCostAmount;

    @Column(name = "forecast_cost_amount", precision = 14, scale = 2)
    private BigDecimal forecastCostAmount;

    @Column(name = "budget_amount", precision = 14, scale = 2)
    private BigDecimal budgetAmount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isActive() {
        return status == SprintStatus.ACTIVE;
    }

    /**
     * Records a scope change after the sprint has started. Before it starts, scope is
     * just planning and is not tracked as churn.
     */
    public void recordScopeChange(BigDecimal points, boolean added) {
        if (status.isScopeMutableWithoutTracking() || points == null
                || points.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        if (added) {
            addedPoints = addedPoints.add(points);
        } else {
            removedPoints = removedPoints.add(points);
        }
    }

    /** Delivered against promised, 0–100. The sprint completion rate reported per team. */
    public BigDecimal completionRate() {
        if (committedPoints == null || committedPoints.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return completedPoints.multiply(BigDecimal.valueOf(100))
                .divide(committedPoints, 2, java.math.RoundingMode.HALF_UP);
    }
}
