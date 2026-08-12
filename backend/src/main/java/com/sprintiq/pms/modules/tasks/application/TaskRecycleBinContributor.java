package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.admin.application.RecycleBinContributor;
import com.sprintiq.pms.modules.admin.application.RecycleBinService;
import com.sprintiq.pms.modules.admin.web.dto.AdminDtos.RecycleBinEntry;
import com.sprintiq.pms.modules.projects.application.ProjectLookupService;
import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Deleted tasks, and their restoration. */
@Component
@RequiredArgsConstructor
public class TaskRecycleBinContributor implements RecycleBinContributor {

    private final TaskRepository tasks;
    private final TaskService taskService;
    private final ProjectLookupService projectLookup;

    @Override
    public EntityType entityType() {
        return EntityType.TASK;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RecycleBinEntry> deletedItems(PageQuery query) {
        return PageResponse.from(tasks.findByDeletedAtIsNotNullOrderByDeletedAtDesc(
                PageRequest.of(query.getPage(), query.getSize())), this::toEntry);
    }

    @Override
    @Transactional
    public void restore(UUID entityId) {
        taskService.restore(entityId);
    }

    private RecycleBinEntry toEntry(Task task) {
        boolean projectAlive = projectLookup.exists(task.getProjectId());
        return new RecycleBinEntry(EntityType.TASK, task.getId(), task.getItemKey(),
                task.getTitle(), task.getProjectId(), task.getUpdatedBy(), null,
                task.getDeletedAt(),
                task.getDeletedAt() == null ? null
                        : task.getDeletedAt().plus(RecycleBinService.RETENTION),
                projectAlive,
                projectAlive ? null : "Restore the project first");
    }
}
