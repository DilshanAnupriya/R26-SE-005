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
import java.time.Duration;
import java.time.Instant;
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

    @Override
    @Transactional(readOnly = true)
    public List<UserEffort> sprintEffortByUser(UUID sprintId) {
        return timeTracking.effortByUserInSprint(sprintId).stream()
                .map(row -> new UserEffort(
                        row.getUserId(),
                        row.getMinutes() == null ? 0L : row.getMinutes(),
                        row.getOvertimeMinutes() == null ? 0L : row.getOvertimeMinutes()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long sprintRemainingEstimateMinutes(UUID sprintId) {
        return taskRepository.findBySprintIdAndDeletedAtIsNull(sprintId).stream()
                .filter(task -> task.getStatus().isOpen())
                .mapToLong(task -> {
                    if (task.getRemainingMinutes() != null) {
                        return task.getRemainingMinutes();
                    }
                    int estimate = task.getEstimateMinutes() == null ? 0 : task.getEstimateMinutes();
                    return Math.max(0, estimate - task.getSpentMinutes());
                })
                .sum();
    }

    @Override
    @Transactional(readOnly = true)
    public long sprintPlannedEstimateMinutes(UUID sprintId) {
        return taskRepository.findBySprintIdAndDeletedAtIsNull(sprintId).stream()
                .mapToLong(task -> task.getEstimateMinutes() == null ? 0L : task.getEstimateMinutes())
                .sum();
    }

    @Override
    @Transactional(readOnly = true)
    public long sprintTaskCount(UUID sprintId) {
        return taskRepository.findBySprintIdAndDeletedAtIsNull(sprintId).size();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> sprintRequiredSkills(UUID sprintId) {
        return taskRepository.findBySprintIdAndDeletedAtIsNull(sprintId).stream()
                .flatMap(task -> task.getRequiredSkills().stream())
                .map(skill -> skill.toLowerCase(Locale.ROOT).trim())
                .filter(skill -> !skill.isBlank())
                .distinct()
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long concurrentAssignments(UUID projectId) {
        return taskRepository.openLoadByAssignee(projectId).stream()
                .mapToLong(TaskRepository.AssigneeLoad::getOpenCount)
                .sum();
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

    /**
     * How many of a sprint's tasks are already behind their own estimate.
     *
     * <p>Two conditions, both measured against the task's estimate rather than against
     * the sprint's end date. A sprint-relative deadline says nothing until the last
     * day; a task with a four-hour estimate that nobody has opened after two hours is
     * behind now, and that is knowable now.</p>
     *
     * <p>Elapsed time runs from {@code startedAt} where the task has been started and
     * from {@code createdAt} where it has not, because an untouched task has no other
     * clock. Logged minutes are used in preference to both once they exist: a task
     * created three weeks ago and worked on for one hour has been worked on for one
     * hour, and charging it for the three weeks would flag every long-lived backlog
     * item the moment it entered a sprint.</p>
     *
     * <p>Tasks with no estimate are excluded entirely rather than counted as on time.
     * Nothing can be late against an estimate that does not exist, and letting them
     * into the denominator would make a sprint look healthier the less of it was
     * estimated.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public SprintSlippage sprintSlippage(UUID sprintId) {
        List<Task> tasks = taskRepository.findBySprintIdAndDeletedAtIsNull(sprintId);
        if (tasks.isEmpty()) {
            return SprintSlippage.none();
        }

        Instant now = Instant.now();
        long estimated = 0;
        long notStartedPastHalf = 0;
        long overEstimate = 0;
        long minutesOver = 0;

        for (Task task : tasks) {
            Integer estimateMinutes = task.getEstimateMinutes();
            if (estimateMinutes == null || estimateMinutes <= 0) {
                continue;
            }
            estimated++;

            long elapsed = elapsedMinutes(task, now);

            if (!task.getStatus().isStarted() && !task.getStatus().isTerminal()
                    && elapsed >= estimateMinutes / 2) {
                notStartedPastHalf++;
            }

            if (!task.getStatus().isTerminal() && elapsed > estimateMinutes) {
                overEstimate++;
                minutesOver += elapsed - estimateMinutes;
            }
        }

        return new SprintSlippage(estimated, notStartedPastHalf, overEstimate, minutesOver);
    }

    /**
     * Minutes this task has been in play.
     *
     * <p>Logged time first, because it is the only figure that reflects work rather than
     * calendar. Falling back to wall-clock from {@code startedAt}, then from
     * {@code createdAt}, keeps a team that does not track time from being invisible to
     * this measure — at the cost of counting weekends, which is why logged minutes win
     * whenever there are any.</p>
     */
    private static long elapsedMinutes(Task task, Instant now) {
        if (task.getSpentMinutes() > 0) {
            return task.getSpentMinutes();
        }
        Instant from = task.getStartedAt() != null ? task.getStartedAt() : task.getCreatedAt();
        if (from == null || !now.isAfter(from)) {
            return 0;
        }
        return Duration.between(from, now).toMinutes();
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
