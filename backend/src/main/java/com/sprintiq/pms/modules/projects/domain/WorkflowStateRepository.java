package com.sprintiq.pms.modules.projects.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStateRepository extends JpaRepository<WorkflowState, UUID> {

    List<WorkflowState> findByProjectIdOrderByPositionAsc(UUID projectId);

    Optional<WorkflowState> findByProjectIdAndCode(UUID projectId, String code);

    boolean existsByProjectIdAndCode(UUID projectId, String code);

    Optional<WorkflowState> findFirstByProjectIdAndCategoryOrderByPositionAsc(UUID projectId,
                                                                             WorkflowCategory category);

    long countByProjectId(UUID projectId);
}
