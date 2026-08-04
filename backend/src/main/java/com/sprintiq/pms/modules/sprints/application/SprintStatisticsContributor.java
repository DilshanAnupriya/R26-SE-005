package com.sprintiq.pms.modules.sprints.application;

import com.sprintiq.pms.modules.projects.application.ProjectStatistics;
import com.sprintiq.pms.modules.projects.application.ProjectStatisticsContributor;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/** Tells the project overview which sprint, if any, is currently running. */
@Component
@RequiredArgsConstructor
public class SprintStatisticsContributor implements ProjectStatisticsContributor {

    private final SprintRepository sprintRepository;

    @Override
    @Transactional(readOnly = true)
    public ProjectStatistics contribute(UUID projectId) {
        return sprintRepository
                .findByProjectIdAndStatusAndDeletedAtIsNull(projectId, SprintStatus.ACTIVE)
                .map(this::toStatistics)
                .orElseGet(ProjectStatistics::empty);
    }

    private ProjectStatistics toStatistics(Sprint sprint) {
        return new ProjectStatistics(0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                sprint.getId(), sprint.getName());
    }
}
