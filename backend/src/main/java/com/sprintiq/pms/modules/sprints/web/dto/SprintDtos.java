package com.sprintiq.pms.modules.sprints.web.dto;

import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.WorkflowStateResponse;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response records for sprints, capacity, boards, burndown and retrospectives. */
public final class SprintDtos {

    private SprintDtos() {}

    // ----------------------------------------------------------------- sprints

    @Schema(name = "CreateSprintRequest")
    public record CreateSprintRequest(
            @NotNull UUID projectId,
            @Size(max = 120) String name,
            @Size(max = 5000) String goal,
            LocalDate startDate,
            LocalDate endDate,
            @Min(1) @Max(60) Short durationDays,
            @DecimalMin("0.0") BigDecimal capacityPoints,
            @DecimalMin("0.0") BigDecimal capacityHours,
            @DecimalMin("0.0") BigDecimal budgetAmount) {}

    @Schema(name = "UpdateSprintRequest")
    public record UpdateSprintRequest(
            @Size(max = 120) String name,
            @Size(max = 5000) String goal,
            LocalDate startDate,
            LocalDate endDate,
            @DecimalMin("0.0") BigDecimal capacityPoints,
            @DecimalMin("0.0") BigDecimal capacityHours,
            @DecimalMin("0.0") BigDecimal budgetAmount,
            @DecimalMin("0.0") BigDecimal plannedCostAmount) {}

    @Schema(name = "SprintResponse")
    public record SprintResponse(
            UUID id,
            UUID projectId,
            int sequenceNo,
            String name,
            String goal,
            SprintStatus status,
            LocalDate startDate,
            LocalDate endDate,
            Short durationDays,
            Short teamSize,
            BigDecimal capacityPoints,
            BigDecimal capacityHours,
            BigDecimal committedPoints,
            BigDecimal completedPoints,
            BigDecimal addedPoints,
            BigDecimal removedPoints,
            BigDecimal completionRate,
            BigDecimal plannedCostAmount,
            BigDecimal forecastCostAmount,
            BigDecimal budgetAmount,
            long storyCount,
            long completedStoryCount,
            Integer daysRemaining,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt) {}

    /** Spillover choice made when a sprint closes. */
    @Schema(name = "CompleteSprintRequest")
    public record CompleteSprintRequest(
            @Schema(description = "What to do with unfinished work",
                    allowableValues = {"BACKLOG", "NEXT_SPRINT", "SPECIFIC_SPRINT"})
            @NotNull SpilloverStrategy spilloverStrategy,
            @Schema(description = "Required when spilloverStrategy is SPECIFIC_SPRINT")
            UUID targetSprintId,
            @Size(max = 5000) String retrospectiveSummary) {}

    @Schema(name = "SpilloverStrategy")
    public enum SpilloverStrategy {
        /** Unfinished work returns to the product backlog. */
        BACKLOG,
        /** Unfinished work moves to the next PLANNED sprint, creating one if needed. */
        NEXT_SPRINT,
        /** Unfinished work moves to a named sprint. */
        SPECIFIC_SPRINT
    }

    @Schema(name = "CompleteSprintResponse")
    public record CompleteSprintResponse(
            SprintResponse sprint,
            int completedStories,
            int spilledStories,
            UUID spilledToSprintId,
            BigDecimal velocity) {}

    @Schema(name = "AddSprintItemsRequest")
    public record AddSprintItemsRequest(@NotEmpty List<@NotNull UUID> storyIds) {}

    // ---------------------------------------------------------------- capacity

    @Schema(name = "SprintCapacityEntry")
    public record SprintCapacityEntry(
            @NotNull UUID userId,
            @DecimalMin("0.0") @DecimalMax("999.0") BigDecimal availableHours,
            @DecimalMin("0.0") @DecimalMax("99.0") BigDecimal absenceDays,
            @Min(0) @Max(100) Short allocationPct,
            @DecimalMin("0.0") BigDecimal committedPoints) {}

    @Schema(name = "UpdateCapacityRequest")
    public record UpdateCapacityRequest(@NotNull List<@Valid SprintCapacityEntry> entries) {}

    @Schema(name = "CapacityMemberResponse")
    public record CapacityMemberResponse(
            UUID userId,
            String fullName,
            String avatarUrl,
            BigDecimal availableHours,
            BigDecimal absenceDays,
            short allocationPct,
            BigDecimal effectiveHours,
            BigDecimal committedPoints) {}

    /** The planning capacity meter: what the team has versus what they have taken on. */
    @Schema(name = "SprintCapacityResponse")
    public record SprintCapacityResponse(
            UUID sprintId,
            BigDecimal totalAvailableHours,
            BigDecimal totalEffectiveHours,
            BigDecimal capacityPoints,
            BigDecimal plannedPoints,
            BigDecimal utilisationPct,
            boolean overCommitted,
            List<CapacityMemberResponse> members) {}

    // ------------------------------------------------------------------- board

    @Schema(name = "BoardColumnResponse")
    public record BoardColumnResponse(
            WorkflowStateResponse state,
            List<StoryResponse> stories,
            int itemCount,
            boolean wipLimitExceeded) {}

    @Schema(name = "SprintBoardResponse")
    public record SprintBoardResponse(
            UUID sprintId,
            String sprintName,
            SprintStatus status,
            List<BoardColumnResponse> columns,
            int totalItems) {}

    // --------------------------------------------------------------- burndown

    @Schema(name = "BurndownPoint")
    public record BurndownPoint(
            LocalDate date,
            BigDecimal idealRemaining,
            BigDecimal actualRemaining,
            BigDecimal completed,
            BigDecimal scopeChange) {}

    @Schema(name = "BurndownResponse")
    public record BurndownResponse(
            UUID sprintId,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal committedPoints,
            BigDecimal completedPoints,
            BigDecimal remainingPoints,
            List<BurndownPoint> points) {}

    @Schema(name = "SprintMetricsResponse")
    public record SprintMetricsResponse(
            UUID sprintId,
            BigDecimal committedPoints,
            BigDecimal completedPoints,
            BigDecimal addedPoints,
            BigDecimal removedPoints,
            BigDecimal completionRate,
            BigDecimal scopeVolatilityPct,
            long storyCount,
            long completedStoryCount,
            long spilloverCount,
            long blockedCount,
            long defectCount,
            long reopenedCount,
            int totalEffortMinutes,
            BigDecimal velocity,
            BigDecimal rollingAverageVelocity) {}

    // ---------------------------------------------------------- retrospective

    @Schema(name = "RetrospectiveRequest")
    public record RetrospectiveRequest(
            List<@Size(max = 1000) String> wentWell,
            List<@Size(max = 1000) String> wentWrong,
            List<Map<String, Object>> actionItems,
            @Min(1) @Max(5) Short teamMood,
            @Size(max = 10000) String summary,
            UUID facilitatorId) {}

    @Schema(name = "RetrospectiveResponse")
    public record RetrospectiveResponse(
            UUID id,
            UUID sprintId,
            List<String> wentWell,
            List<String> wentWrong,
            List<Map<String, Object>> actionItems,
            Short teamMood,
            String summary,
            UUID facilitatorId,
            Instant createdAt,
            Instant updatedAt) {}
}
