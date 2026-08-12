package com.sprintiq.pms.modules.settings.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An outbound connection to another system.
 *
 * <p>{@code secretRef} is a pointer into a secret store, never the secret. A token in a
 * database column is a token in every backup, every replica, every developer's local
 * restore and every log line that ever dumps the row — and the blast radius of that is
 * someone else's system, not just this one.</p>
 */
@Entity
@Table(name = "integrations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Integration extends TenantAwareEntity {

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "provider", nullable = false, length = 48, updatable = false)
    private String provider;

    @Column(name = "display_name", length = 120)
    private String displayName;

    /** Non-secret configuration: channel names, repository paths, event filters. */
    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config = new HashMap<>();

    @Column(name = "secret_ref", length = 255)
    private String secretRef;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IntegrationStatus status = IntegrationStatus.INACTIVE;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;
}
