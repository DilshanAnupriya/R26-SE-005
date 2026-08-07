package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.admin.application.RecycleBinContributor;
import com.sprintiq.pms.modules.admin.application.RecycleBinService;
import com.sprintiq.pms.modules.admin.web.dto.AdminDtos.RecycleBinEntry;
import com.sprintiq.pms.modules.projects.domain.Project;
import com.sprintiq.pms.modules.projects.domain.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Deleted projects, and their restoration. */
@Component
@RequiredArgsConstructor
public class ProjectRecycleBinContributor implements RecycleBinContributor {

    private final ProjectRepository projects;
    private final ProjectService projectService;

    @Override
    public EntityType entityType() {
        return EntityType.PROJECT;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RecycleBinEntry> deletedItems(PageQuery query) {
        return PageResponse.from(projects.findByDeletedAtIsNotNull(
                        PageRequest.of(query.getPage(), query.getSize(),
                                Sort.by(Sort.Direction.DESC, "deletedAt"))),
                this::toEntry);
    }

    @Override
    @Transactional
    public void restore(UUID entityId) {
        projectService.restore(entityId);
    }

    private RecycleBinEntry toEntry(Project project) {
        return new RecycleBinEntry(EntityType.PROJECT, project.getId(), project.getProjectKey(),
                project.getName(), project.getId(), project.getUpdatedBy(), null,
                project.getDeletedAt(),
                project.getDeletedAt() == null ? null
                        : project.getDeletedAt().plus(RecycleBinService.RETENTION),
                true, null);
    }
}
