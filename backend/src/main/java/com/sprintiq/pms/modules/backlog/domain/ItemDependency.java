package com.sprintiq.pms.modules.backlog.domain;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A directed edge between two work items.
 *
 * <p>Polymorphic on both ends, because dependencies cross the hierarchy in practice —
 * a task can block a story, an epic can block another epic. Modelling it with typed
 * foreign keys would need six tables to say one thing.</p>
 */
@Entity
@Table(name = "item_dependencies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemDependency extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20, updatable = false)
    private EntityType sourceType;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private EntityType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "dependency_type", nullable = false, length = 20)
    private DependencyType dependencyType = DependencyType.BLOCKS;
}
