package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One candidate in a Model 3 ranking — a developer, their position, and the scores
 * behind that position.
 *
 * <p>The whole ranking is persisted, not just the winner. Assignment is a decision with
 * consequences for people, and "why was I not suggested for this" deserves an answer
 * that is on record rather than reconstructed. It also makes {@code chosen} meaningful:
 * the interesting training signal is a team that consistently picks the third-ranked
 * candidate, and that is invisible unless the also-rans were stored.</p>
 *
 * <p>{@code overloadRisk} is separated from {@code fitScore} deliberately. The best
 * person for a task is often the person who is already drowning, and a single blended
 * number would quietly recommend burning them out.</p>
 */
@Entity
@Table(name = "resource_allocation_suggestions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AllocationSuggestion extends AppendOnlyTenantEntity {

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "story_id")
    private UUID storyId;

    @Column(name = "candidate_user_id", nullable = false, updatable = false)
    private UUID candidateUserId;

    @Column(name = "rank_position", nullable = false)
    private short rankPosition;

    @Column(name = "fit_score", nullable = false, precision = 4, scale = 3)
    private BigDecimal fitScore;

    /** Expected throughput for this candidate on work of this size, in points per sprint. */
    @Column(name = "predicted_performance", precision = 6, scale = 3)
    private BigDecimal predictedPerformance;

    @Column(name = "consistency_score", precision = 4, scale = 3)
    private BigDecimal consistencyScore;

    @Column(name = "learning_rate", precision = 5, scale = 4)
    private BigDecimal learningRate;

    @Column(name = "skill_match_ratio", precision = 4, scale = 3)
    private BigDecimal skillMatchRatio;

    @Column(name = "current_load_pct", precision = 5, scale = 2)
    private BigDecimal currentLoadPct;

    @Builder.Default
    @Column(name = "overload_risk", nullable = false)
    private boolean overloadRisk = false;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "reasoning", nullable = false, columnDefinition = "jsonb")
    private List<PredictionDriver> reasoning = new ArrayList<>();

    @Column(name = "explanation", length = 1000)
    private String explanation;

    @Column(name = "prediction_id")
    private UUID predictionId;

    /** Set when the team actually assigned this candidate — the M3 ground truth. */
    @Builder.Default
    @Column(name = "chosen", nullable = false)
    private boolean chosen = false;
}
