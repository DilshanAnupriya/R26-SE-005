package com.sprintiq.pms.modules.projects.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Free-form tag applied to epics, stories and tasks.
 *
 * <p>A null {@code projectId} makes the label organization-wide — shared vocabulary
 * such as {@code security} or {@code tech-debt} that should mean the same thing in
 * every project rather than being re-created per project.</p>
 */
@Entity
@Table(name = "labels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Label extends TenantAwareEntity {

    /** Null = organization-wide label available to every project. */
    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Builder.Default
    @Column(name = "color", nullable = false, length = 16)
    private String color = "#64748b";

    public boolean isGlobal() {
        return projectId == null;
    }
}
