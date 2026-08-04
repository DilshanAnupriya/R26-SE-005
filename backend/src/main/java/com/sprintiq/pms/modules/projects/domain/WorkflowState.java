package com.sprintiq.pms.modules.projects.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One column of a project's board. Teams define their own set; {@link WorkflowCategory}
 * keeps reporting meaningful across those differences.
 *
 * <p>{@code wipLimit} is advisory at the API level — exceeding it is surfaced on the
 * board rather than rejected, because a hard block would push people to work outside
 * the tool at exactly the moment the limit is telling you something.</p>
 */
@Entity
@Table(name = "workflow_states")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowState extends TenantAwareEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /** Machine-stable identifier, unique per project. Referenced by automations. */
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private WorkflowCategory category = WorkflowCategory.IN_PROGRESS;

    /** Left-to-right board order. */
    @Builder.Default
    @Column(name = "position", nullable = false)
    private short position = 0;

    @Column(name = "wip_limit")
    private Short wipLimit;

    @Column(name = "color", length = 16)
    private String color;

    public boolean exceedsWipLimit(long itemCount) {
        return wipLimit != null && wipLimit > 0 && itemCount > wipLimit;
    }
}
