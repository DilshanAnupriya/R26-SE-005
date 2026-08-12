package com.sprintiq.pms.modules.projects.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import com.sprintiq.pms.common.domain.EntityType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Join between a {@link Label} and any labelable item.
 *
 * <p>Polymorphic by {@code (entityType, entityId)} rather than three nullable foreign
 * keys: labels apply uniformly to epics, stories and tasks, and adding a fourth
 * labelable type should not mean another column and another migration.</p>
 */
@Entity
@Table(name = "item_labels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemLabel extends TenantAwareEntity {

    @Column(name = "label_id", nullable = false, updatable = false)
    private UUID labelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20, updatable = false)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;
}
