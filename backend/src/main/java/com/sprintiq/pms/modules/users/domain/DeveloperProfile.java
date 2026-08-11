package com.sprintiq.pms.modules.users.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A person's delivery profile: what they said about themselves, and what the system has
 * measured.
 *
 * <p>The two halves are deliberately separated. Seniority, years of experience, hourly
 * rate and weekly capacity are declared — someone types them in. Average velocity,
 * consistency, learning rate, defect and reopen rates are derived from completed work and
 * recomputed on sprint close. Model 3 leans on the measured half and uses the declared
 * half only as a prior, because a title is a claim and a delivery record is evidence.</p>
 *
 * <p>These are also the most sensitive rows in the product. They describe people, they
 * feed a model that recommends who gets which work, and they are visible to anyone who
 * can plan a sprint. That is why every one of them is explainable: the allocation panel
 * shows which metric moved a ranking and by how much, so a suggestion can be argued with
 * rather than merely obeyed.</p>
 */
@Entity
@Table(name = "developer_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeveloperProfile extends TenantAwareEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // ---- declared ------------------------------------------------------------

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "seniority", nullable = false, length = 16)
    private Seniority seniority = Seniority.MID;

    @Builder.Default
    @Column(name = "years_experience", nullable = false, precision = 4, scale = 1)
    private BigDecimal yearsExperience = BigDecimal.ZERO;

    /** Planning rate for cost forecasting. Never used to pay anyone. */
    @Column(name = "hourly_rate", precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    @Builder.Default
    @Column(name = "weekly_capacity_hours", nullable = false, precision = 5, scale = 2)
    private BigDecimal weeklyCapacityHours = BigDecimal.valueOf(40);

    /** 0..1 — the share of a sprint this person is actually available for. */
    @Builder.Default
    @Column(name = "availability_rate", nullable = false, precision = 4, scale = 3)
    private BigDecimal availabilityRate = BigDecimal.ONE;

    // ---- derived, recomputed from delivery history ---------------------------

    @Builder.Default
    @Column(name = "avg_velocity", nullable = false, precision = 8, scale = 2)
    private BigDecimal avgVelocity = BigDecimal.ZERO;

    /**
     * 0..1. How tightly output clusters around this person's own mean — not how fast
     * they are. A steady 4-points-a-sprint developer scores higher than one alternating
     * 1 and 9, and is genuinely easier to plan around.
     */
    @Builder.Default
    @Column(name = "consistency_score", nullable = false, precision = 4, scale = 3)
    private BigDecimal consistencyScore = BigDecimal.valueOf(0.5);

    /** Change in delivered points per sprint. Negative is a real and useful signal. */
    @Builder.Default
    @Column(name = "learning_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal learningRate = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "defect_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal defectRate = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "reopen_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal reopenRate = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "blocked_frequency", nullable = false, precision = 5, scale = 4)
    private BigDecimal blockedFrequency = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "completion_variance", nullable = false, precision = 6, scale = 3)
    private BigDecimal completionVariance = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tasks_completed", nullable = false)
    private int tasksCompleted = 0;

    @Column(name = "last_computed_at")
    private Instant lastComputedAt;

    /**
     * True once there is enough delivery history for the measured metrics to mean
     * anything. Below this the allocation panel says so rather than presenting a
     * confident ranking built on two data points.
     */
    public boolean hasMeaningfulHistory() {
        return tasksCompleted >= 5;
    }
}
