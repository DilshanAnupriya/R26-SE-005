package com.sprintiq.pms.modules.roadmap.web.dto;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.roadmap.domain.Confidence;
import com.sprintiq.pms.modules.roadmap.domain.InitiativeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class RoadmapDtos {

    private RoadmapDtos() {}

    @Schema(name = "CreateInitiativeRequest")
    public record CreateInitiativeRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 10000) String description,
            UUID projectId,
            LocalDate startDate,
            LocalDate targetDate,
            InitiativeStatus status,
            Confidence confidence,
            UUID ownerUserId,
            @Schema(description = "Epics or stories this initiative is made of")
            List<@Valid LinkRequest> items) {}

    @Schema(name = "UpdateInitiativeRequest")
    public record UpdateInitiativeRequest(
            @Size(max = 255) String title,
            @Size(max = 10000) String description,
            UUID projectId,
            LocalDate startDate,
            LocalDate targetDate,
            InitiativeStatus status,
            Confidence confidence,
            UUID ownerUserId,
            Short position) {}

    @Schema(name = "InitiativeLinkRequest")
    public record LinkRequest(
            @NotNull EntityType entityType,
            @NotNull UUID entityId) {}

    @Schema(name = "InitiativeItemResponse")
    public record ItemResponse(
            UUID id,
            EntityType entityType,
            UUID entityId,
            String itemKey,
            String title,
            @Schema(description = "False when the linked item has been deleted")
            boolean exists,
            int totalItems,
            int completedItems,
            BigDecimal progressPct) {}

    @Schema(name = "InitiativeResponse")
    public record InitiativeResponse(
            UUID id,
            UUID projectId,
            String projectKey,
            String projectName,
            String title,
            String description,
            LocalDate startDate,
            LocalDate targetDate,
            @Schema(description = "Derived from the target date — the roadmap column this lands in")
            String quarter,
            InitiativeStatus status,
            Confidence confidence,
            UUID ownerUserId,
            String ownerName,
            BigDecimal progressPct,
            @Schema(description = "Where a steady effort would be today, or null when undated")
            BigDecimal expectedProgressPct,
            boolean overdue,
            Integer daysRemaining,
            short position,
            List<ItemResponse> items,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * The roadmap as it is drawn: lanes of initiatives grouped by project, and the
     * quarter columns they sit in.
     *
     * <p>Assembled server-side rather than left to the client. The quarter columns are
     * a function of what is actually on the roadmap — an empty Q1 should still be a
     * column if something lands in Q2 — and two clients deriving that independently
     * would eventually disagree.</p>
     */
    @Schema(name = "RoadmapResponse")
    public record RoadmapResponse(
            List<String> quarters,
            List<LaneResponse> lanes,
            int totalInitiatives,
            int atRisk,
            int shipped) {}

    @Schema(name = "RoadmapLaneResponse")
    public record LaneResponse(
            @Schema(description = "Null for the cross-project lane")
            UUID projectId,
            String projectKey,
            String name,
            List<InitiativeResponse> initiatives) {}
}
