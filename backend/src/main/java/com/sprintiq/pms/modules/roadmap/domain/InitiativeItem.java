package com.sprintiq.pms.modules.roadmap.domain;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A piece of delivery work that an initiative is made of.
 *
 * <p>This join is the whole reason the roadmap can be trusted: progress is read off
 * the linked epics rather than typed into a card, so a roadmap percentage cannot
 * drift from the board it claims to summarise.</p>
 */
@Entity
@Table(name = "initiative_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InitiativeItem extends TenantAwareEntity {

    @Column(name = "initiative_id", nullable = false, updatable = false)
    private UUID initiativeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20, updatable = false)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;
}
