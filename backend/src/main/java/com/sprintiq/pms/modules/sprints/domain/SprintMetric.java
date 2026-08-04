package com.sprintiq.pms.modules.sprints.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One day's snapshot of a sprint's state — the row a burndown chart plots.
 *
 * <p>A burndown cannot be reconstructed from current data. "How many points remained
 * on day four" is only answerable if someone recorded it on day four; deriving it later
 * from completion timestamps silently loses every scope change, and scope change is
 * precisely what a burndown is supposed to expose. So the snapshot is written as the
 * sprint runs, and the chart reads history rather than recomputing it.</p>
 *
 * <p>Unique per {@code (sprint, date)}: re-snapshotting the same day overwrites rather
 * than appending.</p>
 */
@Entity
@Table(name = "sprint_metrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintMetric extends TenantAwareEntity {

    @Column(name = "sprint_id", nullable = false, updatable = false)
    private UUID sprintId;

    @Column(name = "snapshot_date", nullable = false, updatable = false)
    private LocalDate snapshotDate;

    @Builder.Default
    @Column(name = "committed_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal committedPoints = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "completed_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal completedPoints = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "remaining_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal remainingPoints = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "scope_added_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal scopeAddedPoints = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "scope_removed_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal scopeRemovedPoints = BigDecimal.ZERO;

    /** Where a perfectly linear burn would have been on this day. */
    @Column(name = "ideal_remaining", precision = 8, scale = 2)
    private BigDecimal idealRemaining;

    @Column(name = "velocity", precision = 8, scale = 2)
    private BigDecimal velocity;

    @Builder.Default
    @Column(name = "blocked_count", nullable = false)
    private short blockedCount = 0;

    @Builder.Default
    @Column(name = "reopened_count", nullable = false)
    private short reopenedCount = 0;

    @Builder.Default
    @Column(name = "defect_count", nullable = false)
    private short defectCount = 0;

    @Builder.Default
    @Column(name = "requirement_changes", nullable = false)
    private short requirementChanges = 0;

    /** Scope churn as a fraction of commitment — an M2 over-budget feature. */
    @Builder.Default
    @Column(name = "scope_volatility", nullable = false, precision = 6, scale = 4)
    private BigDecimal scopeVolatility = BigDecimal.ZERO;

    @Column(name = "workload_index", precision = 6, scale = 3)
    private BigDecimal workloadIndex;

    @Builder.Default
    @Column(name = "effort_minutes", nullable = false)
    private int effortMinutes = 0;

    @Builder.Default
    @Column(name = "active_contributors", nullable = false)
    private short activeContributors = 0;
}
