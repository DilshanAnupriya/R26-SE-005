package com.sprintiq.pms.modules.organizations.domain;

import com.sprintiq.pms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A role is either a system role (organizationId == null, shared by all tenants and
 * immutable) or a custom role owned by one organization.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role extends BaseEntity {

    public static final String OWNER = "OWNER";
    public static final String ADMIN = "ADMIN";
    public static final String MANAGER = "MANAGER";
    public static final String MEMBER = "MEMBER";
    public static final String VIEWER = "VIEWER";

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "description", length = 255)
    private String description;

    @Builder.Default
    @Column(name = "system_role", nullable = false)
    private boolean systemRole = false;

    @Builder.Default
    @Column(name = "rank", nullable = false)
    private short rank = 100;

    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    public boolean isEditable() {
        return !systemRole;
    }
}
