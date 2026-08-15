package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OptimizerTaskRepository extends JpaRepository<Task, Long> {
    @EntityGraph(attributePaths = {"developer", "developer.profile"})
    List<Task> findBySprintId(Long sprintId);
    List<Task> findByDeveloperId(Long developerId);
}
