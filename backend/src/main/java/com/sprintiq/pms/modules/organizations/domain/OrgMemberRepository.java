package com.sprintiq.pms.modules.organizations.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgMemberRepository extends JpaRepository<OrgMember, UUID> {

    /**
     * Membership lookups run before the tenant filter is active (login, org switching),
     * so the organization is always passed explicitly here.
     */
    Optional<OrgMember> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<OrgMember> findByUserIdAndStatus(UUID userId, MembershipStatus status);

    Page<OrgMember> findByOrganizationId(UUID organizationId, Pageable pageable);

    long countByOrganizationIdAndStatus(UUID organizationId, MembershipStatus status);

    long countByOrganizationIdAndRoleIdAndStatus(UUID organizationId, UUID roleId, MembershipStatus status);

    boolean existsByOrganizationIdAndUserIdAndStatus(UUID organizationId, UUID userId, MembershipStatus status);

    @Query("SELECT m.userId FROM OrgMember m WHERE m.organizationId = :organizationId AND m.status = 'ACTIVE'")
    List<UUID> findActiveUserIds(@Param("organizationId") UUID organizationId);
}
