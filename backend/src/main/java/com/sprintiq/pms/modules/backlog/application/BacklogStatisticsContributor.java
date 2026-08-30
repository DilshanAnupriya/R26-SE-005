package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.modules.backlog.domain.EpicRepository;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.projects.application.ProjectProgressSource;
import com.sprintiq.pms.modules.projects.application.ProjectStatistics;
import com.sprintiq.pms.modules.projects.application.ProjectStatisticsContributor;
import com.sprintiq.pms.modules.projects.application.WorkflowStateUsageCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Feeds epic, story and point counts into the project overview, and reports story
 * occupancy of board columns.
 *
 * <p>The dependency direction is what matters here: this module knows about the
 * projects module's ports, and the projects module knows nothing about stories. It can
 * therefore be extracted, or this module removed, without either side changing.</p>
 */
@Component
@RequiredArgsConstructor
public class BacklogStatisticsContributor
        implements ProjectStatisticsContributor, WorkflowStateUsageCounter, ProjectProgressSource {

    private final StoryRepository storyRepository;
    private final EpicRepository epicRepository;

    @Override
    @Transactional(readOnly = true)
    public ProjectStatistics contribute(UUID projectId) {
        return new ProjectStatistics(
                epicRepository.countByProjectIdAndDeletedAtIsNull(projectId),
                storyRepository.countByProjectIdAndDeletedAtIsNull(projectId),
                storyRepository.countCompleted(projectId),
                0, 0,
                storyRepository.countBlocked(projectId),
                storyRepository.sumPoints(projectId),
                storyRepository.sumCompletedPoints(projectId),
                null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Progress> progressFor(Collection<UUID> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Progress> progress = new HashMap<>();
        for (Object[] row : storyRepository.sumPointsByProject(projectIds)) {
            progress.put((UUID) row[0],
                    new Progress((BigDecimal) row[1], (BigDecimal) row[2]));
        }
        return progress;
    }

    @Override
    @Transactional(readOnly = true)
    public long countItemsInState(UUID workflowStateId) {
        return storyRepository.countByWorkflowStateIdAndDeletedAtIsNull(workflowStateId);
    }
}
