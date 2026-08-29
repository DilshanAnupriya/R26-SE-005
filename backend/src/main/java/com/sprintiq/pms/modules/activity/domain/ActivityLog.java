package com.sprintiq.pms.modules.activity.domain;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Field-level change history for delivery items — the "who moved this story to
 * In Review, and from what" record behind {@code GET /stories/{id}/history} and
 * the project activity feed.
 *
 * <p>Distinct from {@code AuditLog}: that one is a governance trail covering
 * security-relevant actions across the tenant, this one is product history that
 * users read in the UI. They have different retention and different audiences,
 * so they stay separate tables.</p>
 *
 * <p>Append-only, like the audit trail — no updates, no soft delete.</p>
 */
@Entity
@Table(name = "activity_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "entity_type", nullable = false, length = 24)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    /** Human-readable key ({@code PROJ-142}) denormalised so the feed reads without joins. */
    @Column(name = "entity_key", length = 120)
    private String entityKey;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 48)
    private String action;

    @Column(name = "field_name", length = 60)
    private String fieldName;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    @Column(name = "summary", length = 500)
    private String summary;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = Map.of();

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
