package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.application.port.EffortFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.WorkItemFeatureSource;
import com.sprintiq.pms.modules.backlog.application.StoryTaskCounter;
import com.sprintiq.pms.modules.projects.application.LabelService;
import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What the tasks module contributes to the models: recorded effort, current workload,
 * and the description of a task being ranked for assignment.
 *
 * <p>Three ports on one class because they are three questions about the same rows, and
 * splitting them would mean three components holding the same repository and disagreeing
 * about what "open work" means.</p>
 */
@Component
@RequiredArgsConstructor
public class TaskFeatureProvider implements EffortFeatureSource, WorkItemFeatureSource, StoryTaskCounter {

    /**
     * Minutes of open, estimated work treated as one person's full sprint.
     *
     * <p>Same figure the workload planner uses — ten working days at eight hours. If the
     * two disagreed, the allocation panel would say a developer was comfortable while
     * the board beside it showed them overloaded.</p>
     */
    private static final int FULL_LOAD_MINUTES = 60 * 8 * 10;

    private final TaskRepository taskRepository;
    private final TimeTrackingService timeTracking;
    private final LabelService labelService;

    // -------------------------------------------------------------------- effort

    @Override
    @Transactional(readOnly = true)
    public long sprintEffortMinutes(UUID sprintId) {
        return timeTracking.minutesInSprint(sprintId);
    }

    @Override
    @Transactional(readOnly = true)
    public long sprintOvertimeMinutes(UUID sprintId) {
        return timeTracking.overtimeMinutesInSprint(sprintId);
    }

    /**
     * Open, estimated work per assignee as a percentage of one sprint.
     *
     * <p>Unassigned tasks are excluded rather than distributed. Load is a statement about
     * a person, and spreading unowned work across the team would understate everyone's
     * and hide the fact that nobody has picked it up.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> loadByUser(UUID projectId) {
        Map<UUID, BigDecimal> load = new HashMap<>();
        for (TaskRepository.AssigneeLoad row : taskRepository.openLoadByAssignee(projectId)) {
            long minutes = row.getEstimatedMinutes() == null ? 0L : row.getEstimatedMinutes();
            load.put(row.getAssigneeId(), BigDecimal.valueOf(minutes * 100L)
                    .divide(BigDecimal.valueOf(FULL_LOAD_MINUTES), 2, RoundingMode.HALF_UP));
        }
        return load;
    }

    // -------------------------------------------------------- allocation target

    @Override
    public boolean supports(EntityType entityType) {
        return entityType == EntityType.TASK;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkItemFeatures workItemFor(UUID taskId) {
        Task task = taskRepository.findByIdAndDeletedAtIsNull(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
        // Tasks carry an explicit requiredSkills list; labels are the fallback for
        // teams that tag rather than fill it in. Preferring the explicit field means a
        // team that bothered to state the skills is not overruled by their colour tags.
        List<String> skillTags = task.getRequiredSkills().isEmpty()
                ? labelService.labelsFor(EntityType.TASK, taskId).stream()
                .map(label -> label.name().toLowerCase(Locale.ROOT)).toList()
                : task.getRequiredSkills().stream()
                .map(skill -> skill.toLowerCase(Locale.ROOT)).toList();
        return new WorkItemFeatures(task.getId(), EntityType.TASK, task.getProjectId(),
                task.getSprintId(), task.getItemKey(), task.getTitle(), task.getType().name(),
                task.getPriority(), task.getEstimateMinutes(), null, skillTags,
                task.getAssigneeId(), task.isBlocked());
    }

    // -------------------------------------------------------------- story sizing

    @Override
    @Transactional(readOnly = true)
    public long countTasksForStory(UUID storyId) {
        return taskRepository.countByStoryIdAndDeletedAtIsNull(storyId);
    }
}
