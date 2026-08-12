package com.sprintiq.pms.modules.analytics.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The delivery timeline.
 *
 * <p>Assembled entirely from data that already exists — epic dates, sprint windows,
 * milestone due dates and story assignments. There is no timeline table, deliberately:
 * a second copy of "when is this happening" is a second thing to keep in step, and the
 * first one to be wrong.</p>
 */
public final class TimelineDtos {

    private TimelineDtos() {}

    /** What a bar represents. Drives its shape, not just its label. */
    public enum BarKind {
        EPIC,
        STORY,
        SPRINT,
        /** A point in time rather than a span — drawn as a marker. */
        MILESTONE
    }

    /** Why a row has the dates it has, so an inferred bar is never mistaken for a stated one. */
    public enum DateSource {
        /** The item carries its own start and end. */
        EXPLICIT,
        /** Taken from the sprint the story is committed to. */
        SPRINT,
        /** Only a due date exists; the bar is a marker at that date. */
        DUE_DATE_ONLY,
        /** Inferred from the children, because the parent has no dates of its own. */
        DERIVED
    }

    @Schema(name = "TimelineBar")
    public record BarResponse(
            UUID id,
            String itemKey,
            String title,
            BarKind kind,
            LocalDate start,
            LocalDate end,
            DateSource dateSource,
            @Schema(description = "0-100, or null where completion is not meaningful")
            BigDecimal progressPct,
            String status,
            UUID assigneeId,
            String assigneeName,
            boolean blocked,
            boolean overdue) {}

    @Schema(name = "TimelineGroup")
    public record GroupResponse(
            BarResponse bar,
            List<BarResponse> rows) {}

    @Schema(name = "TimelineWeek")
    public record WeekResponse(
            LocalDate start,
            @Schema(description = "Short label, e.g. 'Aug W2'")
            String label) {}

    @Schema(name = "TimelineResponse")
    public record TimelineResponse(
            LocalDate from,
            LocalDate to,
            LocalDate today,
            List<WeekResponse> weeks,
            List<GroupResponse> groups,
            List<BarResponse> milestones,
            List<BarResponse> sprints,
            @Schema(description = "Items excluded because nothing dates them")
            int undatedCount) {}
}
