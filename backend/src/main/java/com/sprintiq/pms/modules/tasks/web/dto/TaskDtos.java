package com.sprintiq.pms.modules.tasks.web.dto;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.domain.Severity;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.WorkflowStateResponse;
import com.sprintiq.pms.modules.tasks.domain.AssignmentSource;
import com.sprintiq.pms.modules.tasks.domain.TaskStatus;
import com.sprintiq.pms.modules.tasks.domain.TaskType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response records for tasks, watchers, time tracking and the kanban board. */
public final class TaskDtos {

    private TaskDtos() {}

    // ------------------------------------------------------------------- tasks

    @Schema(name = "CreateTaskRequest")
    public record CreateTaskRequest(
            @NotNull UUID projectId,
            UUID storyId,
            @Schema(description = "Set to create this as a subtask of another task") UUID parentTaskId,
            UUID sprintId,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 20000) String description,
            TaskType type,
            Priority priority,
            Severity severity,
            UUID assigneeId,
            @Min(0) @Max(100000) Integer estimateMinutes,
            @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal complexityScore,
            List<@Size(max = 60) String> requiredSkills,
            LocalDate dueDate) {}

    @Schema(name = "UpdateTaskRequest")
    public record UpdateTaskRequest(
            @Size(max = 255) String title,
            @Size(max = 20000) String description,
            TaskType type,
            Priority priority,
            Severity severity,
            UUID storyId,
            UUID sprintId,
            @Min(0) @Max(100000) Integer estimateMinutes,
            @Min(0) @Max(100000) Integer remainingMinutes,
            @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal complexityScore,
            List<@Size(max = 60) String> requiredSkills,
            LocalDate dueDate) {}

    @Schema(name = "UpdateTaskStatusRequest")
    public record UpdateTaskStatusRequest(
            @NotNull TaskStatus status,
            UUID workflowStateId) {}

    /**
     * Assignment, recording whether the AI ranking was taken or overridden — the M3
     * feedback loop.
     */
    @Schema(name = "AssignTaskRequest")
    public record AssignTaskRequest(
            @Schema(description = "Null unassigns the task") UUID assigneeId,
            @Schema(description = "MANUAL, AI_ACCEPTED or AI_OVERRIDDEN; defaults to MANUAL")
            AssignmentSource source,
            @Schema(description = "The allocation prediction being accepted or overridden")
            UUID predictionId) {}

    @Schema(name = "RankTaskRequest")
    public record RankTaskRequest(UUID afterId, UUID beforeId) {}

    @Schema(name = "BlockTaskRequest")
    public record BlockTaskRequest(@NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "TaskResponse")
    public record TaskResponse(
            UUID id,
            UUID projectId,
            UUID storyId,
            UUID parentTaskId,
            UUID sprintId,
            UUID workflowStateId,
            String itemKey,
            String title,
            String description,
            TaskType type,
            TaskStatus status,
            Priority priority,
            Severity severity,
            UUID assigneeId,
            String assigneeName,
            String assigneeAvatarUrl,
            AssignmentSource assignmentSource,
            UUID aiPredictionId,
            UUID reporterId,
            Integer estimateMinutes,
            int spentMinutes,
            Integer remainingMinutes,
            boolean overEstimate,
            BigDecimal complexityScore,
            List<String> requiredSkills,
            LocalDate dueDate,
            String rank,
            boolean blocked,
            String blockedReason,
            short reopenedCount,
            int subtaskCount,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt) {}

    // ---------------------------------------------------------------- watchers

    @Schema(name = "AddWatcherRequest")
    public record AddWatcherRequest(
            @Schema(description = "Omit to watch as the calling user") UUID userId) {}

    @Schema(name = "WatcherResponse")
    public record WatcherResponse(UUID id, UUID userId, String fullName, String avatarUrl, Instant createdAt) {}

    // ------------------------------------------------------------ time entries

    @Schema(name = "LogTimeRequest")
    public record LogTimeRequest(
            @NotNull @Min(1) @Max(1440) Integer minutes,
            @Size(max = 500) String description,
            LocalDate loggedOn,
            Boolean overtime) {}

    @Schema(name = "StartTimerRequest")
    public record StartTimerRequest(
            @NotNull UUID taskId,
            @Size(max = 500) String description) {}

    @Schema(name = "StopTimerRequest")
    public record StopTimerRequest(
            @Size(max = 500) String description,
            Boolean overtime) {}

    @Schema(name = "TimeEntryResponse")
    public record TimeEntryResponse(
            UUID id,
            UUID projectId,
            UUID sprintId,
            UUID entityId,
            UUID userId,
            String userName,
            int minutes,
            boolean overtime,
            String description,
            LocalDate loggedOn,
            Instant startedAt,
            Instant stoppedAt,
            boolean running) {}

    // ------------------------------------------------------------------- board

    @Schema(name = "TaskBoardColumnResponse")
    public record TaskBoardColumnResponse(
            WorkflowStateResponse state,
            List<TaskResponse> tasks,
            int itemCount,
            boolean wipLimitExceeded) {}

    @Schema(name = "TaskBoardResponse")
    public record TaskBoardResponse(
            UUID projectId,
            List<TaskBoardColumnResponse> columns,
            int totalItems) {}

    // ---------------------------------------------------------------- workload

    @Schema(name = "WorkloadEntryResponse")
    public record WorkloadEntryResponse(
            UUID userId,
            String fullName,
            String avatarUrl,
            long openTaskCount,
            long blockedTaskCount,
            int estimatedMinutes,
            int spentMinutes,
            BigDecimal loadPct,
            boolean overAllocated) {}
}
