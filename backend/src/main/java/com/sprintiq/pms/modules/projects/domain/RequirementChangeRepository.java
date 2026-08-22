package com.sprintiq.pms.modules.projects.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RequirementChangeRepository extends JpaRepository<RequirementChange, UUID> {

    List<RequirementChange> findByProjectIdOrderByChangedAtDesc(UUID projectId);

    List<RequirementChange> findBySprintIdOrderByChangedAtDesc(UUID sprintId);

    List<RequirementChange> findByRequirementIdOrderByChangedAtDesc(UUID requirementId);

    long countBySprintId(UUID sprintId);

    /**
     * Changes logged against a project inside a window — how the sprint-level churn
     * figure is assembled for sprints whose changes were logged before the sprint was
     * linked, which is the common case when scope moves during planning.
     */
    @Query("""
            SELECT c FROM RequirementChange c
            WHERE c.projectId = :projectId
              AND c.changedAt >= :from AND c.changedAt < :to
            ORDER BY c.changedAt DESC
            """)
    List<RequirementChange> findInWindow(@Param("projectId") UUID projectId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);
}
