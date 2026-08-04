package com.sprintiq.pms.modules.sprints.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One person's availability for one sprint.
 *
 * <p>Capacity is recorded per member per sprint rather than derived from a standing
 * weekly figure because the thing that actually breaks a sprint plan is the exception:
 * someone on holiday for three days, someone at 50% because they are also on support.
 * A per-sprint row is where that lives, and it is the input the M4 cost forecast and the
 * planning capacity meter both read.</p>
 */
@Entity
@Table(name = "sprint_capacity")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintCapacity extends TenantAwareEntity {

    @Column(name = "sprint_id", nullable = false, updatable = false)
    private UUID sprintId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Gross hours in the sprint before absence and allocation are applied. */
    @Builder.Default
    @Column(name = "available_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal availableHours = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "absence_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal absenceDays = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "allocation_pct", nullable = false)
    private short allocationPct = 100;

    @Builder.Default
    @Column(name = "committed_points", nullable = false, precision = 6, scale = 2)
    private BigDecimal committedPoints = BigDecimal.ZERO;

    /**
     * Hours actually plannable: gross hours less absence, scaled by allocation.
     *
     * @param hoursPerDay the organization's working day, for converting absence days
     */
    public BigDecimal effectiveHours(BigDecimal hoursPerDay) {
        BigDecimal absenceHours = absenceDays.multiply(hoursPerDay);
        BigDecimal net = availableHours.subtract(absenceHours);
        if (net.compareTo(BigDecimal.ZERO) < 0) {
            net = BigDecimal.ZERO;
        }
        return net.multiply(BigDecimal.valueOf(allocationPct))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
