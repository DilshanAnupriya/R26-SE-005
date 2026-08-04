package com.sprintiq.pms.modules.organizations.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCodeAndSystemRoleTrue(String code);

    List<Role> findBySystemRoleTrue();

    /** System roles plus this organization's custom roles. */
    @Query("SELECT r FROM Role r WHERE r.systemRole = true OR r.organizationId = :organizationId ORDER BY r.rank")
    List<Role> findAvailableRoles(@Param("organizationId") UUID organizationId);

    @Query("SELECT r FROM Role r WHERE r.id = :roleId AND (r.systemRole = true OR r.organizationId = :organizationId)")
    Optional<Role> findAccessibleRole(@Param("roleId") UUID roleId, @Param("organizationId") UUID organizationId);

    @Query("SELECT p.code FROM Role r JOIN r.permissions p WHERE r.id = :roleId")
    Set<String> findPermissionCodes(@Param("roleId") UUID roleId);
}
