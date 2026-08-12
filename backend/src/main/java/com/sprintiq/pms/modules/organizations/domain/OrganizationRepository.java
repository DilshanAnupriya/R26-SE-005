package com.sprintiq.pms.modules.organizations.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlugAndDeletedAtIsNull(String slug);

    boolean existsBySlug(String slug);

    Optional<Organization> findByIdAndDeletedAtIsNull(UUID id);

    /** All organizations the given user is an active member of — powers the org switcher. */
    @Query("""
            SELECT o FROM Organization o
            JOIN OrgMember m ON m.organizationId = o.id
            WHERE m.userId = :userId AND m.status = 'ACTIVE' AND o.deletedAt IS NULL
            ORDER BY o.name ASC
            """)
    List<Organization> findAllForUser(@Param("userId") UUID userId);
}
