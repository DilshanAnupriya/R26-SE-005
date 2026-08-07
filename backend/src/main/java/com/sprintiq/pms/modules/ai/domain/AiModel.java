package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.GlobalReferenceEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A registry row describing one deployed model: where it lives, which version is
 * answering, how long it is allowed to take, and whether it is switched on at all.
 *
 * <p>The registry exists so that swapping a model is a configuration change rather than
 * a deployment. When M1 v2 goes live, the endpoint and version move here and every
 * prediction written afterwards records v2 — which is what makes "did accuracy improve
 * after the upgrade" a query rather than an archaeology project.</p>
 */
@Entity
@Table(name = "ai_models")
@GlobalReferenceEntity("Platform model registry: the deployed models are the same for every tenant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, length = 48, updatable = false)
    private AiModelCode code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Semantic version of the served model — not the row's optimistic lock. */
    @Builder.Default
    @Column(name = "version", nullable = false, length = 24)
    private String version = "1.0.0";

    @Column(name = "task_type", nullable = false, length = 24)
    private String taskType;

    @Column(name = "endpoint_url", length = 512)
    private String endpointUrl;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "default_mode", nullable = false, length = 20)
    private InferenceMode defaultMode = InferenceMode.ZERO_SHOT;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Above this, the caller stops waiting and the panel degrades to a manual input.
     * A planning meeting does not pause for an estimate.
     */
    @Builder.Default
    @Column(name = "latency_budget_ms", nullable = false)
    private int latencyBudgetMs = 4000;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = true)
    private Instant updatedAt;

    @Version
    @Column(name = "version_lock", nullable = false)
    private Long versionLock;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
