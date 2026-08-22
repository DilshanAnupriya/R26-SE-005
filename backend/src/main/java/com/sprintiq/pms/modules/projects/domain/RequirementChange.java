package com.sprintiq.pms.modules.projects.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One logged change to what the team agreed to build.
 *
 * <p>{@link Requirement#getChangeCount()} already counts post-approval churn, and this
 * table is not a duplicate of it. A counter cannot say what changed, who asked, which
 * sprint was in flight at the time, or — the part that matters most — whether the change
 * made the sprint harder or easier. Removing scope and adding it are both "a change",
 * and a register that could not tell them apart would report a team's best mid-sprint
 * decision as evidence of instability.</p>
 *
 * <p>{@code riskDelta} is resolved at write time and stored rather than derived on read.
 * Deriving it would mean every past sprint's numbers silently changed the next time the
 * weights were tuned, and a retrospective that rewrites its own history is worse than
 * one holding a stale figure.</p>
 */
@Entity
@Table(name = "requirement_changes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RequirementChange extends TenantAwareEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /**
     * The sprint in flight when the change landed, or null. Scope can move while
     * nothing is running, and that is a materially calmer event — one that should not
     * be charged against any sprint's risk.
     */
    @Column(name = "sprint_id")
    private UUID sprintId;

    /** The requirement being revised, when the change names one. */
    @Column(name = "requirement_id")
    private UUID requirementId;

    /** The story the change lands on, when the change names one. */
    @Column(name = "story_id")
    private UUID storyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 40)
    private RequirementChangeType changeType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "impact_direction", nullable = false, length = 10)
    private ChangeImpactDirection impactDirection = ChangeImpactDirection.INCREASE;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** The account that asked, when the requester has one. */
    @Column(name = "requested_by")
    private UUID requestedBy;

    /** Free text, for the stakeholders who do not have an account here — most of them. */
    @Column(name = "requested_by_name", length = 120)
    private String requestedByName;

    @Builder.Default
    @Column(name = "risk_delta", nullable = false, precision = 6, scale = 2)
    private BigDecimal riskDelta = BigDecimal.ZERO;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @PrePersist
    void defaultChangedAt() {
        if (changedAt == null) {
            changedAt = Instant.now();
        }
    }
}
