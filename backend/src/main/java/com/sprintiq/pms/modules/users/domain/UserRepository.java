package com.sprintiq.pms.modules.users.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailNormalized(String emailNormalized);

    boolean existsByEmailNormalized(String emailNormalized);

    List<User> findByIdIn(Collection<UUID> ids);

    /**
     * Members of one organization, optionally filtered. Joined through org_members so the
     * global users table is never exposed across tenants.
     */
    @Query("""
            SELECT u FROM User u
            JOIN OrgMember m ON m.userId = u.id
            WHERE m.organizationId = :organizationId
              AND (:status IS NULL OR u.status = :status)
              AND (:roleId IS NULL OR m.roleId = :roleId)
              AND (:search IS NULL OR LOWER(u.fullName) LIKE :search OR LOWER(u.email) LIKE :search)
            """)
    Page<User> findOrganizationMembers(@Param("organizationId") UUID organizationId,
                                       @Param("status") UserStatus status,
                                       @Param("roleId") UUID roleId,
                                       @Param("search") String search,
                                       Pageable pageable);

    @Query("""
            SELECT COUNT(u) > 0 FROM User u
            JOIN OrgMember m ON m.userId = u.id
            WHERE u.id = :userId AND m.organizationId = :organizationId AND m.status = 'ACTIVE'
            """)
    boolean isActiveMemberOf(@Param("userId") UUID userId, @Param("organizationId") UUID organizationId);
}
