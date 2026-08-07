package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.collaboration.ItemReferenceResolver;
import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.projects.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Describes projects, milestones and requirements to the collaboration features. */
@Component
@RequiredArgsConstructor
public class ProjectItemReferenceResolver implements ItemReferenceResolver {

    private final ProjectRepository projects;
    private final MilestoneRepository milestones;
    private final RequirementRepository requirements;

    @Override
    public boolean supports(EntityType entityType) {
        return entityType == EntityType.PROJECT
                || entityType == EntityType.MILESTONE
                || entityType == EntityType.REQUIREMENT;
    }

    @Override
    @Transactional(readOnly = true)
    public ItemReference resolve(UUID entityId) {
        return projects.findByIdAndDeletedAtIsNull(entityId)
                .map(project -> new ItemReference(EntityType.PROJECT, project.getId(), project.getId(),
                        project.getProjectKey(), project.getName(),
                        "/projects/%s".formatted(project.getId()),
                        java.util.stream.Stream.of(project.getLeadUserId())
                                .filter(Objects::nonNull).toList()))
                .or(() -> milestones.findByIdAndDeletedAtIsNull(entityId)
                        .map(milestone -> new ItemReference(EntityType.MILESTONE, milestone.getId(),
                                milestone.getProjectId(), null, milestone.getName(),
                                "/projects/%s/milestones/%s"
                                        .formatted(milestone.getProjectId(), milestone.getId()),
                                List.of())))
                .or(() -> requirements.findByIdAndDeletedAtIsNull(entityId)
                        .map(requirement -> new ItemReference(EntityType.REQUIREMENT,
                                requirement.getId(), requirement.getProjectId(),
                                requirement.getReferenceCode(), requirement.getTitle(),
                                "/projects/%s/requirements/%s"
                                        .formatted(requirement.getProjectId(), requirement.getId()),
                                List.of())))
                .orElseThrow(() -> new ResourceNotFoundException("Project item", entityId));
    }
}
