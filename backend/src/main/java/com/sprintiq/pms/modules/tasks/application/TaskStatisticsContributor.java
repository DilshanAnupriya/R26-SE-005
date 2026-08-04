package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.modules.projects.application.ProjectStatistics;
import com.sprintiq.pms.modules.projects.application.ProjectStatisticsContributor;
import com.sprintiq.pms.modules.projects.application.WorkflowStateUsageCounter;
import com.sprintiq.pms.modules.tasks.domain.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Feeds task counts into the project overview and reports task occupancy of board
 * columns.
 *
 * <p>Blocked tasks are counted here and blocked stories in the backlog module; the
 * overview sums both, which is the number a lead actually wants — total impediments,
 * not impediments of one kind.</p>
 */
@Component
@RequiredArgsConstructor
public class TaskStatisticsContributor implements ProjectStatisticsContributor, WorkflowStateUsageCounter {

    private final TaskRepository taskRepository;

    @Override
    @Transactional(readOnly = true)
    public ProjectStatistics contribute(UUID projectId) {
        return new ProjectStatistics(0, 0, 0,
                taskRepository.countByProjectIdAndDeletedAtIsNull(projectId),
                taskRepository.countOpen(projectId),
                taskRepository.countBlocked(projectId),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public long countItemsInState(UUID workflowStateId) {
        return taskRepository.countByWorkflowStateIdAndDeletedAtIsNull(workflowStateId);
    }
}
