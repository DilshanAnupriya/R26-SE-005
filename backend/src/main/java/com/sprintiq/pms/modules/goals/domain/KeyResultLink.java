package com.sprintiq.pms.modules.goals.domain;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * The delivery work that advances a key result.
 *
 * <p>This join is what stops OKRs being a parallel document nobody updates: with it,
 * "which epics move this number" is a query rather than a meeting.</p>
 *
 * <p>Polymorphic on {@code (entityType, entityId)}, matching the other association
 * tables here, so linking a milestone tomorrow needs no new table.</p>
 */
@Entity
@Table(name = "key_result_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KeyResultLink extends TenantAwareEntity {

    @Column(name = "key_result_id", nullable = false, updatable = false)
    private UUID keyResultId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20, updatable = false)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;
}
