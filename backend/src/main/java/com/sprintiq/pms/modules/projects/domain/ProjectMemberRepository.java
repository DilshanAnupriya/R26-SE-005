package com.sprintiq.pms.modules.projects.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    List<ProjectMember> findByProjectId(UUID projectId);

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

    void deleteByProjectIdAndUserId(UUID projectId, UUID userId);

    long countByProjectId(UUID projectId);

    List<ProjectMember> findByUserId(UUID userId);

    /** Assignee pickers and capacity planning ignore stakeholders. */
    @Query("""
            SELECT m.userId FROM ProjectMember m
            WHERE m.projectId = :projectId AND m.stakeholder = false
            """)
    List<UUID> findDeliveryUserIds(@Param("projectId") UUID projectId);
}
