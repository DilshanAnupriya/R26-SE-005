package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The human's verdict on a prediction, and — when they disagreed — the number they
 * used instead.
 *
 * <p>{@code delta} is stored rather than computed on read. It is the column every
 * accuracy report groups by, and recomputing it later would silently change historical
 * figures the day someone corrects a rounding rule.</p>
 */
@Entity
@Table(name = "ai_feedback")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiFeedback extends AppendOnlyTenantEntity {

    @Column(name = "prediction_id", nullable = false, updatable = false)
    private UUID predictionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private FeedbackAction action;

    @Column(name = "predicted_value", precision = 14, scale = 4)
    private BigDecimal predictedValue;

    @Column(name = "final_value", precision = 14, scale = 4)
    private BigDecimal finalValue;

    /** {@code finalValue - predictedValue}; positive means the team went higher. */
    @Column(name = "delta", precision = 14, scale = 4)
    private BigDecimal delta;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
}
