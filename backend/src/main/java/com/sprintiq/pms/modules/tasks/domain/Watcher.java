package com.sprintiq.pms.modules.tasks.domain;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A subscription to an item's notifications.
 *
 * <p>Separate from assignment: the assignee does the work, watchers want to know what
 * happens to it. Polymorphic like the other association tables, so the notification
 * fan-out in Phase 4 has one place to look regardless of item kind.</p>
 */
@Entity
@Table(name = "watchers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Watcher extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20, updatable = false)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
}
