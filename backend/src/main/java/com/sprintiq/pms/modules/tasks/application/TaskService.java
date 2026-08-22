package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.util.LexoRank;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.notifications.application.NotificationService;
import com.sprintiq.pms.modules.notifications.application.RealtimePublisher;
import com.sprintiq.pms.modules.notifications.domain.NotificationType;
import com.sprintiq.pms.modules.activity.application.ChangeSet;
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
import com.sprintiq.pms.modules.projects.application.ItemKeyService;
import com.sprintiq.pms.modules.projects.application.ProjectLookupService;
import com.sprintiq.pms.modules.projects.application.WorkflowStateService;
import com.sprintiq.pms.modules.tasks.domain.*;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.*;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/** Tasks and subtasks: create, edit, move, assign, rank and block. */
@Service
@RequiredArgsConstructor
public class TaskService {

    public static final Set<String> SORT_FIELDS =
            Set.of("rank", "itemKey", "title", "status", "priority", "dueDate", "estimateMinutes",
                    "spentMinutes", "createdAt", "updatedAt");

    /** How deep subtasks may nest. One level of subtask is a checklist; five is a second backlog. */
    private static final int MAX_SUBTASK_DEPTH = 1;

    private final TaskRepository taskRepository;
    private final ProjectLookupService projectLookup;
    private final ItemKeyService itemKeyService;
    private final WorkflowStateService workflowStateService;
    private final UserService userService;
    private final ActivityService activityService;
    private final NotificationService notifications;
    private final RealtimePublisher realtime;
    private final com.sprintiq.pms.modules.backlog.domain.StoryRepository storyRepository;
    private final TaskRiskReporter riskReporter;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> search(UUID projectId, UUID storyId, UUID sprintId, UUID assigneeId,
                                             TaskStatus status, Priority priority, Boolean blocked,
                                             LocalDate dueBefore, boolean topLevelOnly, PageQuery query) {
        Page<Task> page = taskRepository.search(projectId, storyId, sprintId, assigneeId, status, priority,
                blocked, dueBefore, topLevelOnly, query.searchTerm(),
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.ASC, "rank")));
        return PageResponse.from(page, enrichmentFor(page.getContent()));
    }

    @Transactional(readOnly = true)
    public TaskResponse get(UUID taskId) {
        return toResponse(require(taskId));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> subtasks(UUID taskId) {
        require(taskId);
        List<Task> subtasks = taskRepository.findByParentTaskIdAndDeletedAtIsNullOrderByRankAsc(taskId);
        return subtasks.stream().map(enrichmentFor(subtasks)).toList();
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public TaskResponse create(CreateTaskRequest request) {
        projectLookup.requireWritable(request.projectId());

        UUID sprintId = request.sprintId();
        if (request.parentTaskId() != null) {
            Task parent = require(request.parentTaskId());
            if (!parent.getProjectId().equals(request.projectId())) {
                throw new ConflictException("The parent task belongs to a different project");
            }
            if (parent.isSubtask()) {
                throw new BusinessRuleException(
                        "Subtasks cannot be nested more than %d level(s) deep".formatted(MAX_SUBTASK_DEPTH));
            }
            // A subtask always shares its parent's story and sprint; letting them diverge
            // would put a subtask in a sprint its parent is not in.
            sprintId = parent.getSprintId();
        }
        if (request.assigneeId() != null) {
            userService.requireActiveMember(request.assigneeId());
        }

        Task task = Task.builder()
                .projectId(request.projectId())
                .storyId(resolveStoryId(request))
                .parentTaskId(request.parentTaskId())
                .sprintId(sprintId)
                .workflowStateId(workflowStateService.defaultStateId(request.projectId()))
                .itemKey(itemKeyService.allocate(request.projectId()))
                .title(request.title().trim())
                .description(request.description())
                .type(request.type() == null ? TaskType.TASK : request.type())
                .status(TaskStatus.TODO)
                .priority(request.priority() == null ? Priority.MEDIUM : request.priority())
                .severity(request.severity())
                .assigneeId(request.assigneeId())
                .reporterId(TenantContext.userId().orElse(null))
                .estimateMinutes(request.estimateMinutes())
                .remainingMinutes(request.estimateMinutes())
                .complexityScore(request.complexityScore())
                .requiredSkills(request.requiredSkills() == null
                        ? new ArrayList<>() : new ArrayList<>(request.requiredSkills()))
                .dueDate(request.dueDate())
                .rank(nextRank(request.projectId()))
                // The work clock starts the moment the card exists. A task created
                // straight into TODO is already in flight as far as the board is
                // concerned, and starting it later would mean the countdown only
                // began once somebody remembered to drag it.
                .runningSince(Instant.now())
                .build();
        task.setOrganizationId(TenantContext.requireOrganizationId());
        task = taskRepository.save(task);

        activityService.recordCreated(target(task), "%s created".formatted(task.getItemKey()));
        return toResponse(task);
    }

    @Transactional
    public TaskResponse update(UUID taskId, UpdateTaskRequest request) {
        Task task = require(taskId);
        ChangeSet changes = new ChangeSet();

        changes.apply("title", task.getTitle(), trim(request.title()), task::setTitle);
        changes.apply("description", task.getDescription(), request.description(), task::setDescription);
        changes.apply("type", task.getType(), request.type(), task::setType);
        changes.apply("priority", task.getPriority(), request.priority(), task::setPriority);
        changes.apply("severity", task.getSeverity(), request.severity(), task::setSeverity);
        changes.apply("storyId", task.getStoryId(), request.storyId(), task::setStoryId);
        changes.apply("sprintId", task.getSprintId(), request.sprintId(), task::setSprintId);
        changes.apply("dueDate", task.getDueDate(), request.dueDate(), task::setDueDate);
        changes.apply("complexityScore", task.getComplexityScore(), request.complexityScore(),
                task::setComplexityScore);
        changes.apply("remainingMinutes", task.getRemainingMinutes(), request.remainingMinutes(),
                task::setRemainingMinutes);

        if (request.estimateMinutes() != null
                && !request.estimateMinutes().equals(task.getEstimateMinutes())) {
            changes.record("estimateMinutes", task.getEstimateMinutes(), request.estimateMinutes());
            task.setEstimateMinutes(request.estimateMinutes());
            // Re-derive remaining unless the client set it explicitly in the same call.
            if (request.remainingMinutes() == null) {
                task.setRemainingMinutes(Math.max(0, request.estimateMinutes() - task.getSpentMinutes()));
            }
        }
        if (request.requiredSkills() != null) {
            task.setRequiredSkills(new ArrayList<>(request.requiredSkills()));
            changes.record("requiredSkills", null, String.join(", ", request.requiredSkills()));
        }

        if (changes.isEmpty()) {
            return toResponse(task);
        }
        task = taskRepository.save(task);
        activityService.recordChanges(target(task), changes);
        return toResponse(task);
    }

    @Transactional
    public TaskResponse changeStatus(UUID taskId, UpdateTaskStatusRequest request) {
        Optional<Task> optTask = taskRepository.findByIdAndDeletedAtIsNull(taskId);
        if (optTask.isEmpty()) {
            Optional<com.sprintiq.pms.modules.backlog.domain.Story> optStory = storyRepository.findByIdAndDeletedAtIsNull(taskId);
            if (optStory.isPresent()) {
                com.sprintiq.pms.modules.backlog.domain.Story story = optStory.get();
                if (request.workflowStateId() != null) {
                    story.setWorkflowStateId(request.workflowStateId());
                }
                com.sprintiq.pms.modules.backlog.domain.StoryStatus newStatus = switch (request.status()) {
                    case IN_PROGRESS -> com.sprintiq.pms.modules.backlog.domain.StoryStatus.IN_PROGRESS;
                    case DONE -> com.sprintiq.pms.modules.backlog.domain.StoryStatus.DONE;
                    case CANCELLED -> com.sprintiq.pms.modules.backlog.domain.StoryStatus.CANCELLED;
                    default -> com.sprintiq.pms.modules.backlog.domain.StoryStatus.BACKLOG;
                };
                story.setStatus(newStatus);
                story = storyRepository.save(story);

                int estimateMins = story.getStoryPoints() != null ? story.getStoryPoints().intValue() * 60 : 0;
                var assignee = story.getAssigneeId() == null ? null
                        : userService.summariesFor(List.of(story.getAssigneeId())).get(story.getAssigneeId());

                realtime.projectEvent(story.getProjectId(), "task.status_changed",
                        java.util.Map.of("taskId", String.valueOf(story.getId()), "status", request.status().name()));

                return new TaskResponse(
                        story.getId(),
                        story.getProjectId(),
                        story.getId(),
                        null,
                        story.getSprintId(),
                        story.getWorkflowStateId(),
                        story.getItemKey(),
                        story.getTitle(),
                        story.getDescription(),
                        TaskType.TASK,
                        request.status(),
                        story.getPriority(),
                        com.sprintiq.pms.common.domain.Severity.MINOR,
                        story.getAssigneeId(),
                        assignee != null ? assignee.fullName() : null,
                        assignee != null ? assignee.avatarUrl() : null,
                        com.sprintiq.pms.modules.tasks.domain.AssignmentSource.MANUAL,
                        null,
                        story.getReporterId(),
                        estimateMins > 0 ? estimateMins : null,
                        0,
                        null,
                        false,
                        java.math.BigDecimal.valueOf(3.0),
                        List.of(),
                        null,
                        story.getRank(),
                        story.isBlocked(),
                        story.getBlockedReason(),
                        (short) 0,
                        0,
                        null,
                        null,
                        story.getCreatedAt(),
                        story.getUpdatedAt(),
                    0L,
                    null
                );
            }
        }

        Task task = require(taskId);
        TaskStatus previous = task.getStatus();

        if (request.workflowStateId() != null) {
            task.setWorkflowStateId(
                    workflowStateService.requireView(task.getProjectId(), request.workflowStateId()).id());
        } else {
            resolveStateIdForStatus(task.getProjectId(), request.status())
                    .ifPresent(task::setWorkflowStateId);
        }
        task.transitionTo(request.status());
        task = taskRepository.save(task);

        if (previous != request.status()) {
            activityService.recordChange(target(task), ActivityAction.STATUS_CHANGED, "status",
                    previous, request.status(),
                    "%s moved from %s to %s".formatted(task.getItemKey(), previous, request.status()));
            // The board move is evidence about the sprint, not only about the task. Kept
            // in the same transaction as the move so the ledger cannot end up describing
            // a transition that was rolled back.
            riskReporter.statusChanged(task, previous);
        }
        return toResponse(task);
    }

    /**
     * Sends a finished task back for rework.
     *
     * <p>Distinct from dragging the card, though both end in the same place: this is the
     * reviewer's verb, and it says what happened rather than only where the card went.
     * The transition itself does the bookkeeping — the reopen counter moves and the work
     * clock resumes from where it froze rather than restarting.</p>
     */
    @Transactional
    public TaskResponse reopen(UUID taskId, ReopenTaskRequest request) {
        Task task = require(taskId);
        TaskStatus previous = task.getStatus();
        if (!previous.isTerminal()) {
            throw new BusinessRuleException(
                    "%s is not finished, so there is nothing to reopen".formatted(task.getItemKey()));
        }
        TaskStatus target = request == null || request.status() == null
                ? TaskStatus.IN_REVIEW : request.status();
        if (target.isTerminal()) {
            throw new BusinessRuleException("Reopening a task has to move it out of a finished column");
        }

        task.transitionTo(target);
        resolveStateIdForStatus(task.getProjectId(), target).ifPresent(task::setWorkflowStateId);
        task = taskRepository.save(task);

        String note = request == null || request.reason() == null || request.reason().isBlank()
                ? "%s reopened".formatted(task.getItemKey())
                : "%s reopened: %s".formatted(task.getItemKey(), request.reason().trim());
        activityService.recordChange(target(task), ActivityAction.STATUS_CHANGED, "status",
                previous, target, note);
        riskReporter.statusChanged(task, previous);
        return toResponse(task);
    }

    /**
     * Records that a task's countdown has run out while it is still open.
     *
     * <p>Reported by the client rather than polled by a scheduler. The board is already
     * ticking the clock every second to render the countdown, so it knows the moment it
     * crosses zero; a server-side sweep would find out up to a minute later and would
     * run for every sprint in every tenant to catch the handful that matter. The penalty
     * is idempotent by task, so a dozen open tabs all reporting at once cost the sprint
     * exactly what one would.</p>
     */
    @Transactional
    public TaskResponse registerOverdue(UUID taskId) {
        Task task = require(taskId);
        riskReporter.overdue(task);
        return toResponse(task);
    }

    @Transactional
    public TaskResponse assign(UUID taskId, AssignTaskRequest request) {
        Optional<Task> optTask = taskRepository.findByIdAndDeletedAtIsNull(taskId);
        if (optTask.isEmpty()) {
            Optional<com.sprintiq.pms.modules.backlog.domain.Story> optStory = storyRepository.findByIdAndDeletedAtIsNull(taskId);
            if (optStory.isPresent()) {
                com.sprintiq.pms.modules.backlog.domain.Story story = optStory.get();
                UUID previous = story.getAssigneeId();
                if (Objects.equals(previous, request.assigneeId())) {
                    int estimateMins = story.getStoryPoints() != null ? story.getStoryPoints().intValue() * 60 : 0;
                    var assignee = story.getAssigneeId() == null ? null
                            : userService.summariesFor(List.of(story.getAssigneeId())).get(story.getAssigneeId());
                    return toStoryTaskResponse(story, assignee, estimateMins);
                }

                if (request.assigneeId() != null) {
                    userService.requireActiveMember(request.assigneeId());
                }

                story.setAssigneeId(request.assigneeId());
                story = storyRepository.save(story);

                activityService.recordChange(target(story),
                        request.assigneeId() == null ? ActivityAction.UNASSIGNED : ActivityAction.ASSIGNED,
                        "assigneeId", previous, request.assigneeId(),
                        request.assigneeId() == null
                                ? "%s unassigned".formatted(story.getItemKey())
                                : "%s assigned".formatted(story.getItemKey()));

                if (request.assigneeId() != null) {
                    notifications.notify(new NotificationService.Notify(request.assigneeId(),
                            NotificationType.ASSIGNED, "%s was assigned to you".formatted(story.getItemKey()),
                            story.getTitle(), EntityType.STORY, story.getId(),
                            "/projects/%s/stories/%s".formatted(story.getProjectId(), story.getId())));
                }
                if (previous != null && !previous.equals(request.assigneeId())) {
                    notifications.notify(new NotificationService.Notify(previous,
                            NotificationType.UNASSIGNED, "%s is no longer yours".formatted(story.getItemKey()),
                            story.getTitle(), EntityType.STORY, story.getId(),
                            "/projects/%s/stories/%s".formatted(story.getProjectId(), story.getId())));
                }

                Map<String, Object> payload = new HashMap<>();
                payload.put("taskId", story.getId().toString());
                payload.put("assigneeId", request.assigneeId() != null ? request.assigneeId().toString() : null);
                realtime.projectEvent(story.getProjectId(), "task.assigned", payload);

                int estimateMins = story.getStoryPoints() != null ? story.getStoryPoints().intValue() * 60 : 0;
                var assignee = story.getAssigneeId() == null ? null
                        : userService.summariesFor(List.of(story.getAssigneeId())).get(story.getAssigneeId());
                return toStoryTaskResponse(story, assignee, estimateMins);
            }
        }

        Task task = require(taskId);
        UUID previous = task.getAssigneeId();
        AssignmentSource source = request.source() == null ? AssignmentSource.MANUAL : request.source();

        if (Objects.equals(previous, request.assigneeId()) && source == task.getAssignmentSource()) {
            return toResponse(task);
        }
        if (request.assigneeId() != null) {
            userService.requireActiveMember(request.assigneeId());
        }

        task.setAssigneeId(request.assigneeId());
        task.setAssignmentSource(source);
        if (request.predictionId() != null) {
            task.setAiPredictionId(request.predictionId());
        }
        task = taskRepository.save(task);

        activityService.recordChange(target(task),
                request.assigneeId() == null ? ActivityAction.UNASSIGNED : ActivityAction.ASSIGNED,
                "assigneeId", previous, request.assigneeId(),
                request.assigneeId() == null
                        ? "%s unassigned".formatted(task.getItemKey())
                        : "%s assigned (%s)".formatted(task.getItemKey(), source));

        // Both sides are told. Being given work matters most, but so does discovering
        // that something you were tracking is no longer yours.
        if (request.assigneeId() != null) {
            notifications.notify(new NotificationService.Notify(request.assigneeId(),
                    NotificationType.ASSIGNED, "%s was assigned to you".formatted(task.getItemKey()),
                    task.getTitle(), EntityType.TASK, task.getId(),
                    "/projects/%s/tasks/%s".formatted(task.getProjectId(), task.getId())));
        }
        if (previous != null && !previous.equals(request.assigneeId())) {
            notifications.notify(new NotificationService.Notify(previous,
                    NotificationType.UNASSIGNED, "%s is no longer yours".formatted(task.getItemKey()),
                    task.getTitle(), EntityType.TASK, task.getId(),
                    "/projects/%s/tasks/%s".formatted(task.getProjectId(), task.getId())));
        }

        // The board reflects the change for everyone watching it, without a poll.
        realtime.projectEvent(task.getProjectId(), "task.assigned",
                java.util.Map.of("taskId", task.getId(),
                        "assigneeId", String.valueOf(request.assigneeId())));

        return toResponse(task);
    }

    private TaskResponse toStoryTaskResponse(com.sprintiq.pms.modules.backlog.domain.Story story,
                                            com.sprintiq.pms.modules.users.application.UserSummary assignee,
                                            int estimateMins) {
        TaskStatus status = switch (story.getStatus()) {
            case IN_PROGRESS -> TaskStatus.IN_PROGRESS;
            case DONE -> TaskStatus.DONE;
            case CANCELLED -> TaskStatus.CANCELLED;
            default -> TaskStatus.TODO;
        };

        return new TaskResponse(
                story.getId(),
                story.getProjectId(),
                story.getId(),
                null,
                story.getSprintId(),
                story.getWorkflowStateId(),
                story.getItemKey(),
                story.getTitle(),
                story.getDescription(),
                TaskType.TASK,
                status,
                story.getPriority(),
                com.sprintiq.pms.common.domain.Severity.MINOR,
                story.getAssigneeId(),
                assignee != null ? assignee.fullName() : null,
                assignee != null ? assignee.avatarUrl() : null,
                AssignmentSource.MANUAL,
                null,
                story.getReporterId(),
                estimateMins > 0 ? estimateMins : null,
                0,
                null,
                false,
                java.math.BigDecimal.valueOf(3.0),
                List.of(),
                null,
                story.getRank(),
                story.isBlocked(),
                story.getBlockedReason(),
                (short) 0,
                0,
                null,
                null,
                story.getCreatedAt(),
                story.getUpdatedAt(),
                    0L,
                    null
        );
    }

    @Transactional
    public TaskResponse rank(UUID taskId, RankTaskRequest request) {
        Task task = require(taskId);
        String after = neighbourRank(request.afterId(), task.getProjectId());
        String before = neighbourRank(request.beforeId(), task.getProjectId());
        try {
            task.setRank(LexoRank.between(after, before));
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException("Cannot rank between the supplied neighbours: " + ex.getMessage());
        }
        return toResponse(taskRepository.save(task));
    }

    @Transactional
    public TaskResponse block(UUID taskId, BlockTaskRequest request) {
        Task task = require(taskId);
        task.setBlocked(true);
        task.setBlockedReason(request.reason().trim());
        if (task.getBlockedAt() == null) {
            task.setBlockedAt(Instant.now());
        }
        // Move the task out of the in-progress column too: work that is blocked is not
        // being done, and leaving it in place quietly inflates every WIP measure.
        if (task.getStatus().isStarted()) {
            task.transitionTo(TaskStatus.BLOCKED);
        }
        task = taskRepository.save(task);

        activityService.record(target(task), ActivityAction.BLOCKED,
                "%s blocked: %s".formatted(task.getItemKey(), request.reason().trim()));
        return toResponse(task);
    }

    @Transactional
    public TaskResponse unblock(UUID taskId) {
        Task task = require(taskId);
        if (!task.isBlocked() && task.getStatus() != TaskStatus.BLOCKED) {
            return toResponse(task);
        }
        if (task.getStatus() == TaskStatus.BLOCKED) {
            task.transitionTo(TaskStatus.IN_PROGRESS);
        }
        task.setBlocked(false);
        task.setBlockedReason(null);
        task.setBlockedAt(null);
        task = taskRepository.save(task);

        activityService.record(target(task), ActivityAction.UNBLOCKED,
                "%s unblocked".formatted(task.getItemKey()));
        return toResponse(task);
    }

    /** Deleting a parent takes its subtasks with it — a subtask alone means nothing. */
    @Transactional
    public void delete(UUID taskId) {
        Task task = require(taskId);
        Instant now = Instant.now();

        List<Task> subtasks = taskRepository.findByParentTaskIdAndDeletedAtIsNullOrderByRankAsc(taskId);
        subtasks.forEach(subtask -> subtask.setDeletedAt(now));
        taskRepository.saveAll(subtasks);

        task.setDeletedAt(now);
        taskRepository.save(task);
        // A task in the recycle bin is not evidence about the sprint any more. Its
        // state-based charges go with it; the one-off events it caused while it existed
        // stay, because they did happen.
        subtasks.forEach(riskReporter::removed);
        riskReporter.removed(task);
        activityService.recordDeleted(target(task), subtasks.isEmpty()
                ? "%s moved to the recycle bin".formatted(task.getItemKey())
                : "%s and %d subtask(s) moved to the recycle bin"
                        .formatted(task.getItemKey(), subtasks.size()));
    }

    @Transactional
    public TaskResponse restore(UUID taskId) {
        Task task = taskRepository.findByIdAndDeletedAtIsNotNull(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Deleted task", taskId));
        task.setDeletedAt(null);
        task = taskRepository.save(task);
        activityService.record(target(task), ActivityAction.RESTORED,
                "%s restored".formatted(task.getItemKey()));
        return toResponse(task);
    }

    // ------------------------------------------------------------------ internals

    Task require(UUID taskId) {
        return taskRepository.findByIdAndDeletedAtIsNull(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
    }

    /** A subtask inherits its parent's story unless the request names one explicitly. */
    private UUID resolveStoryId(CreateTaskRequest request) {
        if (request.storyId() != null) {
            return request.storyId();
        }
        return request.parentTaskId() == null ? null : require(request.parentTaskId()).getStoryId();
    }

    private Optional<UUID> resolveStateIdForStatus(UUID projectId, TaskStatus status) {
        String category = switch (status) {
            case TODO, BLOCKED -> "TODO";
            case IN_PROGRESS, IN_REVIEW -> "IN_PROGRESS";
            case DONE -> "DONE";
            case CANCELLED -> "CANCELLED";
        };
        return workflowStateService.viewsFor(projectId).stream()
                .filter(state -> state.category().equals(category))
                .findFirst()
                .map(com.sprintiq.pms.modules.projects.application.WorkflowStateView::id);
    }

    private String neighbourRank(UUID neighbourId, UUID projectId) {
        if (neighbourId == null) {
            return null;
        }
        Task neighbour = require(neighbourId);
        if (!neighbour.getProjectId().equals(projectId)) {
            throw new ConflictException("Neighbour %s belongs to a different project"
                    .formatted(neighbour.getItemKey()));
        }
        return neighbour.getRank();
    }

    private String nextRank(UUID projectId) {
        return taskRepository.findMaxRank(projectId).map(LexoRank::after).orElse(LexoRank.INITIAL);
    }

    private ActivityService.Target target(Task task) {
        return ActivityService.Target.of(EntityType.TASK, task.getId(), task.getItemKey(),
                task.getProjectId());
    }

    private ActivityService.Target target(com.sprintiq.pms.modules.backlog.domain.Story story) {
        return ActivityService.Target.of(EntityType.STORY, story.getId(), story.getItemKey(),
                story.getProjectId());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    // ------------------------------------------------------------------ mapping

    TaskResponse toResponse(Task task) {
        return toResponse(task, task.getAssigneeId() == null ? null
                : userService.summariesFor(List.of(task.getAssigneeId())).get(task.getAssigneeId()));
    }

    /** Batch enrichment, for the same reason as the backlog's — boards render many rows. */
    Function<Task, TaskResponse> enrichmentFor(List<Task> tasks) {
        Map<UUID, UserSummary> assignees = userService.summariesFor(
                tasks.stream().map(Task::getAssigneeId).filter(Objects::nonNull).toList());
        return task -> toResponse(task,
                task.getAssigneeId() == null ? null : assignees.get(task.getAssigneeId()));
    }

    private TaskResponse toResponse(Task task, UserSummary assignee) {
        int subtaskCount = task.isSubtask()
                ? 0
                : taskRepository.findByParentTaskIdAndDeletedAtIsNullOrderByRankAsc(task.getId()).size();

        return new TaskResponse(task.getId(), task.getProjectId(), task.getStoryId(), task.getParentTaskId(),
                task.getSprintId(), task.getWorkflowStateId(), task.getItemKey(), task.getTitle(),
                task.getDescription(), task.getType(), task.getStatus(), task.getPriority(),
                task.getSeverity(), task.getAssigneeId(),
                assignee == null ? null : assignee.fullName(),
                assignee == null ? null : assignee.avatarUrl(),
                task.getAssignmentSource(), task.getAiPredictionId(), task.getReporterId(),
                task.getEstimateMinutes(), task.getSpentMinutes(), task.getRemainingMinutes(),
                task.isOverEstimate(), task.getComplexityScore(), task.getRequiredSkills(),
                task.getDueDate(), task.getRank(), task.isBlocked(), task.getBlockedReason(),
                task.getReopenedCount(), subtaskCount, task.getStartedAt(), task.getCompletedAt(),
                task.getCreatedAt(), task.getUpdatedAt(),
                task.getElapsedSeconds(), task.getRunningSince());
    }
}
