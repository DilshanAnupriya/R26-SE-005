package com.sprintiq.pms.modules.organizations.domain;

import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Sub-container inside a tenant. Projects always live in exactly one workspace. */
@Entity
@Table(name = "workspaces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workspace extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "workspace_key", nullable = false, length = 16)
    private String workspaceKey;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "icon", length = 64)
    private String icon;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private boolean defaultWorkspace = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
