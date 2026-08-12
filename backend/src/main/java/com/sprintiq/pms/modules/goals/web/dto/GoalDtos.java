package com.sprintiq.pms.modules.goals.web.dto;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.goals.domain.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response records for goals, key results and check-ins. */
public final class GoalDtos {

    private GoalDtos() {}

    // ------------------------------------------------------------------- goals

    @Schema(name = "CreateGoalRequest")
    public record CreateGoalRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 10000) String description,
            @Size(max = 32) String period,
            LocalDate startDate,
            LocalDate targetDate,
            GoalLevel level,
            GoalStatus status,
            UUID ownerUserId,
            UUID workspaceId,
            UUID projectId,
            UUID parentGoalId,
            @Schema(description = "Key results to create alongside the objective")
            List<@Valid CreateKeyResultRequest> keyResults) {}

    @Schema(name = "UpdateGoalRequest")
    public record UpdateGoalRequest(
            @Size(max = 255) String title,
            @Size(max = 10000) String description,
            @Size(max = 32) String period,
            LocalDate startDate,
            LocalDate targetDate,
            GoalLevel level,
            GoalStatus status,
            UUID ownerUserId,
            UUID parentGoalId) {}

    @Schema(name = "GoalResponse")
    public record GoalResponse(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            UUID parentGoalId,
            String title,
            String description,
            String period,
            LocalDate startDate,
            LocalDate targetDate,
            GoalStatus status,
            GoalLevel level,
            UUID ownerUserId,
            String ownerName,
            String ownerAvatarUrl,
            BigDecimal progressPct,
            boolean overdue,
            Integer daysRemaining,
            List<KeyResultResponse> keyResults,
            Instant createdAt,
            Instant updatedAt) {}

    // ------------------------------------------------------------- key results

    @Schema(name = "CreateKeyResultRequest")
    public record CreateKeyResultRequest(
            @NotBlank @Size(max = 255) String title,
            MetricType metricType,
            @Size(max = 24) String unit,
            BigDecimal startValue,
            @NotNull BigDecimal targetValue,
            MetricDirection direction,
            UUID ownerUserId) {}

    @Schema(name = "UpdateKeyResultRequest")
    public record UpdateKeyResultRequest(
            @Size(max = 255) String title,
            MetricType metricType,
            @Size(max = 24) String unit,
            BigDecimal startValue,
            BigDecimal targetValue,
            MetricDirection direction,
            UUID ownerUserId,
            @Min(0) @Max(999) Short position) {}

    @Schema(name = "KeyResultResponse")
    public record KeyResultResponse(
            UUID id,
            UUID goalId,
            String title,
            MetricType metricType,
            String unit,
            BigDecimal startValue,
            BigDecimal currentValue,
            BigDecimal targetValue,
            MetricDirection direction,
            GoalStatus status,
            BigDecimal progressPct,
            boolean achieved,
            UUID ownerUserId,
            String ownerName,
            short position,
            List<LinkedItemResponse> linkedItems,
            Instant updatedAt) {}

    /**
     * Recording a measurement.
     *
     * <p>A note is encouraged rather than required: the number says what happened,
     * the note says why, and a check-in without the why is the one nobody can act
     * on at review.</p>
     */
    @Schema(name = "CheckinRequest")
    public record CheckinRequest(
            @NotNull BigDecimal value,
            @Min(1) @Max(5) Short confidence,
            @Size(max = 1000) String note) {}

    @Schema(name = "CheckinResponse")
    public record CheckinResponse(
            UUID id,
            UUID keyResultId,
            BigDecimal value,
            BigDecimal previousValue,
            BigDecimal delta,
            Short confidence,
            String note,
            UUID recordedBy,
            String recordedByName,
            Instant recordedAt) {}

    // ------------------------------------------------------------------- links

    @Schema(name = "LinkItemRequest")
    public record LinkItemRequest(
            @NotNull EntityType entityType,
            @NotNull UUID entityId) {}

    @Schema(name = "LinkedItemResponse")
    public record LinkedItemResponse(
            UUID id,
            EntityType entityType,
            UUID entityId,
            String itemKey,
            String title) {}

    // ----------------------------------------------------------------- summary

    /** The header figures on the Goals screen. */
    @Schema(name = "GoalSummaryResponse")
    public record GoalSummaryResponse(
            String period,
            long total,
            long onTrack,
            long atRisk,
            long offTrack,
            long achieved,
            BigDecimal averageProgress,
            List<String> availablePeriods) {}
}
