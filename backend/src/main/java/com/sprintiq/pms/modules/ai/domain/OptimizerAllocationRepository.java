package com.sprintiq.pms.modules.ai.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OptimizerAllocationRepository extends JpaRepository<Allocation, Long> {
    List<Allocation> findBySprint_Id(Long sprintId);
    List<Allocation> findByTask_Id(Long taskId);
    List<Allocation> findByTask_IdAndStatusNot(Long taskId, String status);
    boolean existsBySprintAndTaskAndDeveloper(Sprint sprint, Task task, Developer developer);
    List<Allocation> findByDeveloperAndSprint_IdAndStatusAndTask_IdNot(
        Developer developer, Long sprintId, String status, Long excludeTaskId
    );
}
