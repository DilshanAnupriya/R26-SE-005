package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Model 2 verdict on one sprint: the probability, the yes/no call at the model's
 * threshold, what drove it and what to do about it.
 *
 * <p>Assessments accumulate rather than being overwritten. A sprint whose over-budget
 * risk climbed from 0.2 to 0.7 over four days is telling the team something that the
 * single current figure cannot, and it is the sequence a retrospective wants to look
 * at.</p>
 *
 * <p>{@code acknowledgedBy} closes the loop: a warning that nobody has seen and a
 * warning the team has read and decided to accept look identical on a dashboard
 * otherwise, and only one of them should keep shouting. It is the single field on this
 * otherwise-immutable row that may be written after insert, and it only ever moves from
 * unset to set — the assessment itself is never revised.</p>
 */
@Entity
@Table(name = "risk_assessments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskAssessment extends AppendOnlyTenantEntity {

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "sprint_id", nullable = false, updatable = false)
    private UUID sprintId;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_type", nullable = false, length = 32, updatable = false)
    private RiskType riskType;

    @Column(name = "risk_score", nullable = false, precision = 4, scale = 3)
    private BigDecimal riskScore;

    /** The call at the model's decision threshold, kept beside the raw score. */
    @Column(name = "risk_label", nullable = false)
    private boolean riskLabel;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private RiskSeverity severity = RiskSeverity.LOW;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "drivers", nullable = false, columnDefinition = "jsonb")
    private List<PredictionDriver> drivers = new ArrayList<>();

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "mitigations", nullable = false, columnDefinition = "jsonb")
    private List<String> mitigations = new ArrayList<>();

    @Column(name = "explanation", length = 1000)
    private String explanation;

    @Column(name = "prediction_id")
    private UUID predictionId;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    public void acknowledge(UUID userId) {
        acknowledgedBy = userId;
        acknowledgedAt = Instant.now();
    }
}
