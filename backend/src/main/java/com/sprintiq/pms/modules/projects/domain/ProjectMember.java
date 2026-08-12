package com.sprintiq.pms.modules.projects.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Someone's place on one project team. Membership here does not grant permissions —
 * those come from the org role — it declares participation, which drives assignment
 * pickers, capacity planning and the workload board.
 */
@Entity
@Table(name = "project_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectMember extends TenantAwareEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "project_role", nullable = false, length = 32)
    private ProjectRole projectRole = ProjectRole.DEVELOPER;

    @Builder.Default
    @Column(name = "is_stakeholder", nullable = false)
    private boolean stakeholder = false;

    /** Share of this person's capacity the project can plan against, 0–100. */
    @Builder.Default
    @Column(name = "allocation_pct", nullable = false)
    private short allocationPct = 100;
}
