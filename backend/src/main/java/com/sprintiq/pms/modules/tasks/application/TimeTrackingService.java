package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manual time entries and the start/stop timer.
 *
 * <p>A person may have at most one timer running. That is not a technical limit but a
 * data-integrity one: two concurrent timers double-count the same wall-clock hour, and
 * every downstream number — the timesheet, sprint effort, the M4 cost forecast — reads
 * that total as if it were real.</p>
 */
@Service
@RequiredArgsConstructor
public class TimeTrackingService {

    public static final Set<String> SORT_FIELDS = Set.of("loggedOn", "minutes", "createdAt");

    private final TimeEntryRepository timeEntryRepository;
    private final TaskRepository taskRepository;
    private final UserService userService;
    private final ActivityService activityService;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<TimeEntryResponse> listForTask(UUID taskId) {
        requireTask(taskId);
        List<TimeEntry> entries = timeEntryRepository
                .findByEntityTypeAndEntityIdOrderByLoggedOnDesc(EntityType.TASK, taskId);
        return enrich(entries);
    }

    @Transactional(readOnly = true)
    public PageResponse<TimeEntryResponse> timesheet(UUID projectId, UUID userId, UUID sprintId,
                                                     LocalDate from, LocalDate to, PageQuery query) {
        Page<TimeEntry> page = timeEntryRepository.search(projectId, userId, sprintId, from, to,
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "loggedOn")));
        Map<UUID, UserSummary> people = userService.summariesFor(
                page.getContent().stream().map(TimeEntry::getUserId).toList());
        return PageResponse.from(page, entry -> toResponse(entry, people.get(entry.getUserId())));
    }

    @Transactional(readOnly = true)
    public TimeEntryResponse runningTimer() {
        UUID userId = TenantContext.requireUserId();
        return timeEntryRepository.findRunningTimer(userId)
                .map(entry -> toResponse(entry, userService.summariesFor(List.of(userId)).get(userId)))
                .orElse(null);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public TimeEntryResponse log(UUID taskId, LogTimeRequest request) {
        Task task = requireTask(taskId);
        UUID userId = TenantContext.requireUserId();

        TimeEntry entry = TimeEntry.builder()
                .projectId(task.getProjectId())
                .sprintId(task.getSprintId())
                .entityType(EntityType.TASK)
                .entityId(taskId)
                .userId(userId)
                .minutes(request.minutes())
                .overtime(Boolean.TRUE.equals(request.overtime()))
                .description(request.description())
                .loggedOn(request.loggedOn() == null ? LocalDate.now() : request.loggedOn())
                .build();
        entry.setOrganizationId(TenantContext.requireOrganizationId());
        entry = timeEntryRepository.save(entry);

        applyToTask(task, request.minutes());
        activityService.record(target(task), ActivityAction.TIME_LOGGED,
                "%d minute(s) logged on %s".formatted(request.minutes(), task.getItemKey()));
        return toResponse(entry, userService.summariesFor(List.of(userId)).get(userId));
    }

    /**
     * Starts a timer. If one is already running it is stopped first rather than
     * rejected — switching tasks is the normal case, and forcing a stop-then-start
     * round trip is how people end up leaving the old timer running all afternoon.
     */
    @Transactional
    public TimeEntryResponse startTimer(StartTimerRequest request) {
        Task task = requireTask(request.taskId());
        UUID userId = TenantContext.requireUserId();

        timeEntryRepository.findRunningTimer(userId).ifPresent(running -> finish(running, null, false));

        TimeEntry entry = TimeEntry.builder()
                .projectId(task.getProjectId())
                .sprintId(task.getSprintId())
                .entityType(EntityType.TASK)
                .entityId(request.taskId())
                .userId(userId)
                .minutes(0)
                .description(request.description())
                .startedAt(Instant.now())
                .loggedOn(LocalDate.now())
                .build();
        entry.setOrganizationId(TenantContext.requireOrganizationId());
        entry = timeEntryRepository.save(entry);

        return toResponse(entry, userService.summariesFor(List.of(userId)).get(userId));
    }

    @Transactional
    public TimeEntryResponse stopTimer(StopTimerRequest request) {
        UUID userId = TenantContext.requireUserId();
        TimeEntry entry = timeEntryRepository.findRunningTimer(userId)
                .orElseThrow(() -> new ConflictException("No timer is currently running"));

        finish(entry, request.description(), Boolean.TRUE.equals(request.overtime()));

        Task task = requireTask(entry.getEntityId());
        activityService.record(target(task), ActivityAction.TIME_LOGGED,
                "%d minute(s) logged on %s from a timer".formatted(entry.getMinutes(), task.getItemKey()));
        return toResponse(entry, userService.summariesFor(List.of(userId)).get(userId));
    }

    @Transactional
    public void delete(UUID timeEntryId) {
        TimeEntry entry = timeEntryRepository.findById(timeEntryId)
                .orElseThrow(() -> new ResourceNotFoundException("Time entry", timeEntryId));
        // Only the author may remove their own record of effort; anything else is an
        // edit to someone else's timesheet and belongs behind time:manage, checked in
        // the controller.
        if (!entry.getUserId().equals(TenantContext.requireUserId())
                && !TenantContext.hasPermission("time:manage")) {
            throw new BusinessRuleException("You can only delete your own time entries");
        }

        taskRepository.findByIdAndDeletedAtIsNull(entry.getEntityId()).ifPresent(task -> {
            task.setSpentMinutes(Math.max(0, task.getSpentMinutes() - entry.getMinutes()));
            if (task.getEstimateMinutes() != null) {
                task.setRemainingMinutes(Math.max(0, task.getEstimateMinutes() - task.getSpentMinutes()));
            }
            taskRepository.save(task);
        });
        timeEntryRepository.delete(entry);
    }

    /** Total effort logged against a sprint — read by sprint metrics and the M4 model. */
    @Transactional(readOnly = true)
    public long minutesInSprint(UUID sprintId) {
        return timeEntryRepository.sumMinutesInSprint(sprintId);
    }

    /** Minutes one person logged, optionally narrowed to a project or a sprint. */
    @Transactional(readOnly = true)
    public long minutesLoggedBy(UUID userId, UUID projectId, UUID sprintId) {
        return timeEntryRepository.sumMinutesLoggedBy(userId, projectId, sprintId);
    }

    @Transactional(readOnly = true)
    public long overtimeMinutesInSprint(UUID sprintId) {
        return timeEntryRepository.sumOvertimeMinutesInSprint(sprintId);
    }

    @Transactional(readOnly = true)
    public List<TimeEntryRepository.UserEffortSummary> effortByUserInSprint(UUID sprintId) {
        return timeEntryRepository.effortByUserInSprint(sprintId);
    }

    // ------------------------------------------------------------------ internals

    private void finish(TimeEntry entry, String description, boolean overtime) {
        entry.stop(Instant.now());
        if (description != null) {
            entry.setDescription(description);
        }
        entry.setOvertime(overtime);
        timeEntryRepository.save(entry);

        taskRepository.findByIdAndDeletedAtIsNull(entry.getEntityId())
                .ifPresent(task -> applyToTask(task, entry.getMinutes()));
    }

    private void applyToTask(Task task, int minutes) {
        task.logMinutes(minutes);
        taskRepository.save(task);
    }

    private Task requireTask(UUID taskId) {
        return taskRepository.findByIdAndDeletedAtIsNull(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
    }

    private ActivityService.Target target(Task task) {
        return ActivityService.Target.of(EntityType.TASK, task.getId(), task.getItemKey(),
                task.getProjectId());
    }

    private List<TimeEntryResponse> enrich(List<TimeEntry> entries) {
        Map<UUID, UserSummary> people = userService.summariesFor(
                entries.stream().map(TimeEntry::getUserId).toList());
        return entries.stream().map(entry -> toResponse(entry, people.get(entry.getUserId()))).toList();
    }

    private TimeEntryResponse toResponse(TimeEntry entry, UserSummary user) {
        return new TimeEntryResponse(entry.getId(), entry.getProjectId(), entry.getSprintId(),
                entry.getEntityId(), entry.getUserId(), user == null ? null : user.fullName(),
                entry.getMinutes(), entry.isOvertime(), entry.getDescription(), entry.getLoggedOn(),
                entry.getStartedAt(), entry.getStoppedAt(), entry.isRunning());
    }
}
