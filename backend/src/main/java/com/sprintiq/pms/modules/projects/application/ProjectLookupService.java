package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.projects.domain.Project;
import com.sprintiq.pms.modules.projects.domain.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-side entry point the other delivery modules use to resolve a project.
 *
 * <p>Exists so that backlog, sprints and tasks never touch {@code ProjectRepository}
 * directly. That keeps "does this project exist, and may I add work to it?" answered
 * in one place — including the tenant check, which comes free from the Hibernate filter
 * but only if the query goes through this module.</p>
 */
@Service
@RequiredArgsConstructor
public class ProjectLookupService {

    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public ProjectRef require(UUID projectId) {
        return toRef(requireEntity(projectId));
    }

    /**
     * Resolves a project and refuses if it can no longer take new work. Called on every
     * create path so an archived project cannot quietly accumulate stories.
     */
    @Transactional(readOnly = true)
    public ProjectRef requireWritable(UUID projectId) {
        ProjectRef ref = require(projectId);
        if (!ref.acceptsNewWork()) {
            throw new ConflictException(
                    "Project %s is %s and cannot accept new work items".formatted(ref.projectKey(), ref.status()));
        }
        return ref;
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID projectId) {
        return projectRepository.findByIdAndDeletedAtIsNull(projectId).isPresent();
    }

    private Project requireEntity(UUID projectId) {
        return projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    private ProjectRef toRef(Project project) {
        return new ProjectRef(project.getId(), project.getWorkspaceId(), project.getProjectKey(),
                project.getName(), project.getStatus().name(), project.getDefaultSprintDays(),
                project.getCurrency());
    }
}
