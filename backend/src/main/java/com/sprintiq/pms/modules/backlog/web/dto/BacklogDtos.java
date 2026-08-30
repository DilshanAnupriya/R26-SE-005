package com.sprintiq.pms.modules.backlog.web.dto;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.domain.Severity;
import com.sprintiq.pms.modules.backlog.domain.*;
import com.sprintiq.pms.modules.projects.domain.ChangeImpactDirection;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeType;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.LabelResponse;
import com.sprintiq.pms.modules.projects.web.dto.RequirementChangeDtos.RequirementChangeResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response records for epics, stories, backlog ranking and dependencies. */
public final class BacklogDtos {

    private BacklogDtos() {}

    // ------------------------------------------------------------------- epics

    @Schema(name = "CreateEpicRequest")
    public record CreateEpicRequest(
            @NotNull UUID projectId,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 10000) String description,
            EpicStatus status,
            Priority priority,
            UUID milestoneId,
            @Size(max = 16) String color,
            LocalDate startDate,
            LocalDate targetDate) {}

    @Schema(name = "UpdateEpicRequest")
    public record UpdateEpicRequest(
            @Size(max = 255) String title,
            @Size(max = 10000) String description,
            EpicStatus status,
            Priority priority,
            UUID milestoneId,
            @Size(max = 16) String color,
            LocalDate startDate,
            LocalDate targetDate) {}

    @Schema(name = "EpicResponse")
    public record EpicResponse(
            UUID id,
            UUID projectId,
            UUID milestoneId,
            String itemKey,
            String title,
            String description,
            EpicStatus status,
            Priority priority,
            String color,
            LocalDate startDate,
            LocalDate targetDate,
            String rank,
            long storyCount,
            long completedStoryCount,
            BigDecimal totalPoints,
            List<LabelResponse> labels,
            Instant createdAt,
            Instant updatedAt) {}

    // ----------------------------------------------------------------- stories

    @Schema(name = "CreateStoryRequest")
    public record CreateStoryRequest(
            @NotNull UUID projectId,
            UUID epicId,
            UUID requirementId,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 20000) String description,
            @Size(max = 10000) String acceptanceCriteria,
            StoryType type,
            Priority priority,
            Severity severity,
            @DecimalMin("0.0") @DecimalMax("999.0") BigDecimal storyPoints,
            @Min(0) @Max(100) Short businessValue,
            UUID assigneeId,
            UUID sprintId,
            LocalDate dueDate,
            List<@Size(max = 255) String> readyChecklist,
            List<@Size(max = 255) String> doneChecklist,
            List<UUID> labelIds) {}

    @Schema(name = "ApplyStoryChangeRequest",
            description = "Edits the one field a change type describes, and logs the edit in the "
                    + "requirement-change register. Which way the risk moves is read from the "
                    + "edit itself, not from the request — only OTHER, which touches no field, "
                    + "falls back to impactDirection.")
    public record ApplyStoryChangeRequest(
            @NotNull RequirementChangeType changeType,

            @Schema(description = "The story's new priority. PRIORITY_CHANGED only.")
            Priority newPriority,

            @Schema(description = "The story's new description. DESCRIPTION_UPDATED only.")
            String newDescription,

            @Schema(description = "The story's new acceptance criteria. "
                    + "ACCEPTANCE_CRITERIA_CHANGED only.")
            String newAcceptanceCriteria,

            @Schema(description = "The story's new estimate. SCOPE_ADDED and SCOPE_REMOVED "
                    + "only, and it has to move the way the type claims.")
            @DecimalMin("0.0") BigDecimal newStoryPoints,

            @Schema(description = "Used only for OTHER, which changes no field to read a "
                    + "direction from. Ignored everywhere else.")
            ChangeImpactDirection impactDirection,

            @Schema(description = "Why this changed, for the register")
            @Size(max = 4000) String description,

            @Schema(description = "Who asked for it, when they have no account here")
            @Size(max = 120) String requestedByName,

            @Schema(description = "Charge the story's sprint, when it is in one. False for a "
                    + "change agreed outside the sprint that is running.")
            boolean attachToSprint) {}

    @Schema(name = "StoryChangeResponse",
            description = "The story as it now stands, and the register entry that recorded "
                    + "the edit — including the risk points it cost or returned")
    public record StoryChangeResponse(StoryResponse story, RequirementChangeResponse change) {}

    @Schema(name = "UpdateStoryRequest")
    public record UpdateStoryRequest(
            @Size(max = 255) String title,
            @Size(max = 20000) String description,
            @Size(max = 10000) String acceptanceCriteria,
            StoryType type,
            Priority priority,
            Severity severity,
            UUID epicId,
            UUID requirementId,
            @Min(0) @Max(100) Short businessValue,
            LocalDate dueDate,
            List<@Size(max = 255) String> readyChecklist,
            List<@Size(max = 255) String> doneChecklist) {}

    @Schema(name = "UpdateStoryStatusRequest")
    public record UpdateStoryStatusRequest(
            @NotNull StoryStatus status,
            @Schema(description = "Board column to move to; defaults to the column mapped to the status")
            UUID workflowStateId) {}

    @Schema(name = "AssignStoryRequest")
    public record AssignStoryRequest(
            @Schema(description = "Null unassigns the story") UUID assigneeId) {}

    /**
     * Setting an estimate. {@code source} lets the client say whether this number came
     * from accepting the AI suggestion or from overriding it — the distinction the M1
     * feedback loop is built on.
     */
    @Schema(name = "EstimateStoryRequest")
    public record EstimateStoryRequest(
            @NotNull @DecimalMin("0.0") @DecimalMax("999.0") BigDecimal storyPoints,
            @Schema(description = "MANUAL, AI_ACCEPTED or AI_OVERRIDDEN; defaults to MANUAL")
            EstimateSource source,
            @Schema(description = "The prediction being accepted or overridden") UUID predictionId) {}

    @Schema(name = "BlockStoryRequest")
    public record BlockStoryRequest(@NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "StoryTaskBudgetResponse",
            description = "How much of a story's estimated time its tasks have claimed")
    public record StoryTaskBudgetResponse(
            UUID storyId,
            String itemKey,
            BigDecimal storyPoints,
            /** Hours one point is worth on this project. */
            BigDecimal hoursPerPoint,
            /** Points x rate, in minutes. Null when the story has no estimate. */
            Long budgetMinutes,
            long consumedMinutes,
            /** Negative once the breakdown has outgrown the story. Null when unsized. */
            Long remainingMinutes,
            /** Minutes beyond budget, or zero. */
            long overrunMinutes,
            boolean overBudget,
            long taskCount,
            /**
             * Tasks carrying no estimate. They consume nothing measurable, so a
             * comfortable-looking remainder with several of these is not evidence of
             * slack — it is evidence of unsized work.
             */
            long unestimatedTasks) {}

    @Schema(name = "StoryResponse")
    public record StoryResponse(
            UUID id,
            UUID projectId,
            UUID epicId,
            UUID sprintId,
            UUID requirementId,
            UUID workflowStateId,
            String itemKey,
            String title,
            String description,
            String acceptanceCriteria,
            StoryType type,
            StoryStatus status,
            Priority priority,
            Severity severity,
            BigDecimal storyPoints,
            AiEstimate aiEstimate,
            EstimateSource estimateSource,
            Short businessValue,
            String rank,
            UUID assigneeId,
            String assigneeName,
            String assigneeAvatarUrl,
            UUID reporterId,
            LocalDate dueDate,
            boolean blocked,
            String blockedReason,
            short reopenedCount,
            List<String> readyChecklist,
            List<String> doneChecklist,
            List<LabelResponse> labels,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt) {}

    /** The model's suggestion, kept beside the human's number so the UI can compare them. */
    @Schema(name = "AiEstimate")
    public record AiEstimate(
            BigDecimal points,
            BigDecimal confidence,
            BigDecimal lowerBound,
            BigDecimal upperBound,
            /**
             * The model's number before Fibonacci snapping. Null for a suggestion
             * recorded before this was stored, and for models that do not snap.
             */
            BigDecimal rawPoints,
            UUID predictionId) {

        public static AiEstimate of(BigDecimal points, BigDecimal confidence, BigDecimal lower,
                                    BigDecimal upper, BigDecimal rawPoints, UUID predictionId) {
            return points == null && predictionId == null
                    ? null
                    : new AiEstimate(points, confidence, lower, upper, rawPoints, predictionId);
        }
    }

    // ----------------------------------------------------------------- backlog

    /**
     * Moves items to a new position. Neighbours are given rather than an index because
     * an index is only valid against the exact list the client was looking at — with
     * concurrent editors that list is stale by the time the request lands, whereas
     * "between these two items" stays meaningful.
     */
    @Schema(name = "RankRequest")
    public record RankRequest(
            @NotEmpty List<@NotNull UUID> itemIds,
            @Schema(description = "Item to place these after; null means the top of the backlog")
            UUID afterId,
            @Schema(description = "Item to place these before; null means the bottom of the backlog")
            UUID beforeId) {}

    @Schema(name = "RankedItemResponse")
    public record RankedItemResponse(UUID id, String rank) {}

    /** Field-by-field bulk edit. Null fields are left alone across every selected item. */
    @Schema(name = "BulkUpdateRequest")
    public record BulkUpdateRequest(
            @NotEmpty List<@NotNull UUID> itemIds,
            StoryStatus status,
            Priority priority,
            UUID epicId,
            UUID sprintId,
            UUID assigneeId,
            @Schema(description = "Set true to clear the sprint assignment (move back to the backlog)")
            Boolean removeFromSprint,
            List<UUID> addLabelIds) {}

    @Schema(name = "BulkUpdateResponse")
    public record BulkUpdateResponse(int updatedCount, List<UUID> updatedIds, List<String> skipped) {}

    // ------------------------------------------------------------ dependencies

    @Schema(name = "CreateDependencyRequest")
    public record CreateDependencyRequest(
            @NotNull EntityType targetType,
            @NotNull UUID targetId,
            @NotNull DependencyType dependencyType) {}

    @Schema(name = "DependencyResponse")
    public record DependencyResponse(
            UUID id,
            EntityType sourceType,
            UUID sourceId,
            String sourceKey,
            EntityType targetType,
            UUID targetId,
            String targetKey,
            DependencyType dependencyType,
            Instant createdAt) {}

    // ---------------------------------------------------------------- history

    @Schema(name = "HistoryEntryResponse")
    public record HistoryEntryResponse(
            UUID id,
            String action,
            String fieldName,
            String oldValue,
            String newValue,
            String summary,
            UUID actorId,
            Instant createdAt) {}
}
