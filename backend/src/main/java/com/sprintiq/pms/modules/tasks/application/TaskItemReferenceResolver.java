package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.collaboration.ItemReferenceResolver;
import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Describes tasks to the collaboration features.
 *
 * <p>Watchers are folded into the interested list here rather than left to each caller.
 * Watching is the explicit opt-in that makes the default audience narrow enough to be
 * useful, so it has to be honoured wherever an item is commented on or a file is
 * attached — not only wherever someone remembered.</p>
 */
@Component
@RequiredArgsConstructor
public class TaskItemReferenceResolver implements ItemReferenceResolver {

    private final TaskRepository tasks;
    private final WatcherService watchers;

    @Override
    public boolean supports(EntityType entityType) {
        return entityType == EntityType.TASK;
    }

    @Override
    @Transactional(readOnly = true)
    public ItemReference resolve(UUID entityId) {
        Task task = tasks.findByIdAndDeletedAtIsNull(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", entityId));

        List<UUID> interested = new ArrayList<>(watchers.watcherIdsFor(EntityType.TASK, entityId));
        interested.add(task.getAssigneeId());
        interested.add(task.getReporterId());

        return new ItemReference(EntityType.TASK, task.getId(), task.getProjectId(),
                task.getItemKey(), task.getTitle(),
                "/projects/%s/tasks/%s".formatted(task.getProjectId(), task.getId()),
                interested.stream().filter(Objects::nonNull).distinct().toList());
    }
}
