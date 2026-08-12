package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import com.sprintiq.pms.common.domain.EntityType;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One inference call, recorded in full: the exact features that went in, what came
 * back, how long it took, and whether it was served from cache.
 *
 * <p>{@code requestPayload} is stored rather than summarised because it is the training
 * set. A year from now, "what did the model see when it said 13" has to be answerable
 * from this row alone — the story it described will have been edited a dozen times by
 * then, so reconstructing the feature vector from live data would produce a different
 * vector and a misleading answer.</p>
 *
 * <p>Everything derived from a prediction — a risk assessment, an allocation ranking, a
 * cost forecast — points back here, so a suspicious number on a dashboard is always one
 * join away from the inputs that produced it.</p>
 */
@Entity
@Table(name = "ai_predictions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiPrediction extends AppendOnlyTenantEntity {

    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_code", nullable = false, length = 48, updatable = false)
    private AiModelCode modelCode;

    @Column(name = "model_version", nullable = false, length = 24, updatable = false)
    private String modelVersion;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private InferenceMode mode = InferenceMode.ZERO_SHOT;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 24, updatable = false)
    private EntityType entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestPayload = new HashMap<>();

    /** Regression output. Null for pure classifiers, which fill {@link #predictedLabel}. */
    @Column(name = "predicted_value", precision = 14, scale = 4)
    private BigDecimal predictedValue;

    @Column(name = "predicted_label", length = 48)
    private String predictedLabel;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "lower_bound", precision = 14, scale = 4)
    private BigDecimal lowerBound;

    @Column(name = "upper_bound", precision = 14, scale = 4)
    private BigDecimal upperBound;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "drivers", nullable = false, columnDefinition = "jsonb")
    private List<PredictionDriver> drivers = new ArrayList<>();

    @Column(name = "explanation", length = 1000)
    private String explanation;

    @Type(JsonType.class)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private Map<String, Object> rawResponse;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PredictionStatus status = PredictionStatus.SUCCESS;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Builder.Default
    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit = false;

    /**
     * Digest of the feature vector. Two requests with the same hash have the same
     * answer, which is what makes the cache safe: it keys on what the model actually
     * sees rather than on the entity id, so editing a story invalidates its estimate
     * automatically instead of serving a stale one until a TTL expires.
     */
    @Column(name = "feature_hash", length = 64)
    private String featureHash;

    @Column(name = "created_by")
    private UUID createdBy;

    public boolean isUsable() {
        return status.isUsable();
    }
}
