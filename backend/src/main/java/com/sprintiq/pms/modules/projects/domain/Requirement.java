package com.sprintiq.pms.modules.projects.domain;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A tracked requirement, linked to the stories that implement it (traceability matrix).
 *
 * <p>{@code changeCount} is not bookkeeping — it is the raw signal behind the M2
 * requirement-change risk model. A sprint drawing on requirements that keep moving is
 * the pattern that model is trained to catch, so the counter is maintained on every
 * post-approval edit rather than derived after the fact from activity history.</p>
 */
@Entity
@Table(name = "requirements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Requirement extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Stable citation code ({@code REQ-014}), unique per project. */
    @Column(name = "reference_code", nullable = false, length = 32)
    private String referenceCode;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    private RequirementType type = RequirementType.FUNCTIONAL;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private Priority priority = Priority.MEDIUM;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RequirementStatus status = RequirementStatus.DRAFT;

    /** Where the requirement came from: a stakeholder, a regulation, a support ticket. */
    @Column(name = "source", length = 120)
    private String source;

    @Builder.Default
    @Column(name = "change_count", nullable = false)
    private int changeCount = 0;

    @Column(name = "last_changed_at")
    private Instant lastChangedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Records churn against this requirement. Only counts once the requirement is
     * baselined — edits while it is still a DRAFT are authoring, not volatility.
     */
    public void registerChange() {
        if (status.isBaselined()) {
            changeCount += 1;
            lastChangedAt = Instant.now();
        }
    }
}
