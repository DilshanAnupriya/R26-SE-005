package com.sprintiq.pms.modules.projects.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MilestoneRepository extends JpaRepository<Milestone, UUID> {

    Optional<Milestone> findByIdAndDeletedAtIsNull(UUID id);

    List<Milestone> findByProjectIdAndDeletedAtIsNullOrderByDueDateAsc(UUID projectId);

    List<Milestone> findByProjectIdAndStatusAndDeletedAtIsNull(UUID projectId, MilestoneStatus status);

    long countByProjectIdAndStatusAndDeletedAtIsNull(UUID projectId, MilestoneStatus status);
}
