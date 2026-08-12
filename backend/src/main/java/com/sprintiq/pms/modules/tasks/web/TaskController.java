package com.sprintiq.pms.modules.tasks.web;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.tasks.application.TaskService;
import com.sprintiq.pms.modules.tasks.application.TimeTrackingService;
import com.sprintiq.pms.modules.tasks.application.WatcherService;
import com.sprintiq.pms.modules.tasks.domain.TaskStatus;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Tasks", description = "Tasks, subtasks, watchers and time entries")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final WatcherService watcherService;
    private final TimeTrackingService timeTrackingService;

    @Operation(summary = "Search tasks")
    @PreAuthorize("@perm.has('task:read')")
    @GetMapping
    public PageResponse<TaskResponse> list(@RequestParam(required = false) UUID projectId,
                                           @RequestParam(required = false) UUID storyId,
                                           @RequestParam(required = false) UUID sprintId,
                                           @RequestParam(required = false) UUID assigneeId,
                                           @RequestParam(required = false) TaskStatus status,
                                           @RequestParam(required = false) Priority priority,
                                           @RequestParam(required = false) Boolean blocked,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore,
                                           @RequestParam(defaultValue = "false") boolean topLevelOnly,
                                           @Valid PageQuery query) {
        return taskService.search(projectId, storyId, sprintId, assigneeId, status, priority, blocked,
                dueBefore, topLevelOnly, query);
    }

    @Operation(summary = "Get one task")
    @PreAuthorize("@perm.has('task:read')")
    @GetMapping("/{taskId}")
    public TaskResponse get(@PathVariable UUID taskId) {
        return taskService.get(taskId);
    }

    @Operation(summary = "Create a task or subtask")
    @PreAuthorize("@perm.has('task:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public TaskResponse create(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(request);
    }

    @Operation(summary = "Update a task")
    @PreAuthorize("@perm.has('task:manage')")
    @PatchMapping("/{taskId}")
    public TaskResponse update(@PathVariable UUID taskId, @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.update(taskId, request);
    }

    @Operation(summary = "Move a task to another status or board column")
    @PreAuthorize("@perm.has('task:manage')")
    @PatchMapping("/{taskId}/status")
    public TaskResponse changeStatus(@PathVariable UUID taskId,
                                     @Valid @RequestBody UpdateTaskStatusRequest request) {
        return taskService.changeStatus(taskId, request);
    }

    @Operation(summary = "Assign a task, recording whether the AI ranking was taken")
    @PreAuthorize("@perm.has('task:assign')")
    @PatchMapping("/{taskId}/assign")
    public TaskResponse assign(@PathVariable UUID taskId, @Valid @RequestBody AssignTaskRequest request) {
        return taskService.assign(taskId, request);
    }

    @Operation(summary = "Reorder a task relative to its neighbours")
    @PreAuthorize("@perm.has('task:manage')")
    @PatchMapping("/{taskId}/rank")
    public TaskResponse rank(@PathVariable UUID taskId, @Valid @RequestBody RankTaskRequest request) {
        return taskService.rank(taskId, request);
    }

    @Operation(summary = "Subtasks of a task")
    @PreAuthorize("@perm.has('task:read')")
    @GetMapping("/{taskId}/subtasks")
    public List<TaskResponse> subtasks(@PathVariable UUID taskId) {
        return taskService.subtasks(taskId);
    }

    @Operation(summary = "Create a subtask under this task")
    @PreAuthorize("@perm.has('task:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{taskId}/subtasks")
    public TaskResponse createSubtask(@PathVariable UUID taskId,
                                      @Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(new CreateTaskRequest(request.projectId(), request.storyId(), taskId,
                request.sprintId(), request.title(), request.description(), request.type(),
                request.priority(), request.severity(), request.assigneeId(), request.estimateMinutes(),
                request.complexityScore(), request.requiredSkills(), request.dueDate()));
    }

    @Operation(summary = "Flag a task as blocked")
    @PreAuthorize("@perm.has('task:manage')")
    @PostMapping("/{taskId}/block")
    public TaskResponse block(@PathVariable UUID taskId, @Valid @RequestBody BlockTaskRequest request) {
        return taskService.block(taskId, request);
    }

    @Operation(summary = "Clear the blocked flag")
    @PreAuthorize("@perm.has('task:manage')")
    @PostMapping("/{taskId}/unblock")
    public TaskResponse unblock(@PathVariable UUID taskId) {
        return taskService.unblock(taskId);
    }

    @Operation(summary = "Move a task and its subtasks to the recycle bin")
    @PreAuthorize("@perm.has('task:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{taskId}")
    public void delete(@PathVariable UUID taskId) {
        taskService.delete(taskId);
    }

    @Operation(summary = "Restore a soft-deleted task")
    @PreAuthorize("@perm.has('task:manage')")
    @PostMapping("/{taskId}/restore")
    public TaskResponse restore(@PathVariable UUID taskId) {
        return taskService.restore(taskId);
    }

    // ---------------------------------------------------------------- watchers

    @Operation(summary = "Users watching this task")
    @PreAuthorize("@perm.has('task:read')")
    @GetMapping("/{taskId}/watchers")
    public List<WatcherResponse> watchers(@PathVariable UUID taskId) {
        return watcherService.list(EntityType.TASK, taskId);
    }

    @Operation(summary = "Watch this task")
    @PreAuthorize("@perm.has('task:read')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{taskId}/watchers")
    public WatcherResponse addWatcher(@PathVariable UUID taskId,
                                      @RequestBody(required = false) AddWatcherRequest request) {
        return watcherService.add(EntityType.TASK, taskId, request == null ? null : request.userId());
    }

    @Operation(summary = "Stop watching this task")
    @PreAuthorize("@perm.has('task:read')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{taskId}/watchers/{userId}")
    public void removeWatcher(@PathVariable UUID taskId, @PathVariable UUID userId) {
        watcherService.remove(EntityType.TASK, taskId, userId);
    }

    // ------------------------------------------------------------- time entries

    @Operation(summary = "Time logged against this task")
    @PreAuthorize("@perm.has('task:read')")
    @GetMapping("/{taskId}/time-entries")
    public List<TimeEntryResponse> timeEntries(@PathVariable UUID taskId) {
        return timeTrackingService.listForTask(taskId);
    }

    @Operation(summary = "Log time against this task")
    @PreAuthorize("@perm.has('time:log')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{taskId}/time-entries")
    public TimeEntryResponse logTime(@PathVariable UUID taskId, @Valid @RequestBody LogTimeRequest request) {
        return timeTrackingService.log(taskId, request);
    }
}
