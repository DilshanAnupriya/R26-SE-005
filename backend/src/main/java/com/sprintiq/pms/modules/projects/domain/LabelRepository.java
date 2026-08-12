package com.sprintiq.pms.modules.projects.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabelRepository extends JpaRepository<Label, UUID> {

    /** A project's own labels plus the organization-wide ones it inherits. */
    @Query("""
            SELECT l FROM Label l
            WHERE l.projectId = :projectId OR l.projectId IS NULL
            ORDER BY l.name ASC
            """)
    List<Label> findAvailableForProject(@Param("projectId") UUID projectId);

    List<Label> findByProjectIdIsNullOrderByNameAsc();

    Optional<Label> findByProjectIdAndName(UUID projectId, String name);

    boolean existsByOrganizationIdAndProjectIdAndName(UUID organizationId, UUID projectId, String name);

    List<Label> findByIdIn(List<UUID> ids);
}
