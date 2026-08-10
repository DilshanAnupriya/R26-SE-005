package com.sprintiq.pms.modules.analytics.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The delivery calendar.
 *
 * <p>Like the timeline, gathered rather than stored. Everything on it is a date some
 * module already records — a sprint's boundaries, a milestone's due date, a story's
 * deadline, an initiative's target, a goal's cycle end. There is no events table,
 * which means there is nothing to keep in step and nothing that can disagree with the
 * board it came from.</p>
 *
 * <p>It follows that this calendar shows commitments, not meetings. Anything a person
 * would type in — a workshop, someone's leave — is not here, and the screen says so
 * rather than leaving a reader to wonder why the week looks empty.</p>
 */
public final class CalendarDtos {

    private CalendarDtos() {}

    /** What kind of commitment this is. Drives the colour and the icon, not just the label. */
    public enum EntryKind {
        SPRINT_START,
        SPRINT_END,
        MILESTONE,
        STORY_DUE,
        INITIATIVE_TARGET,
        GOAL_TARGET
    }

    @Schema(name = "CalendarEntry")
    public record EntryResponse(
            UUID id,
            EntryKind kind,
            LocalDate date,
            String title,
            String itemKey,
            UUID projectId,
            String projectKey,
            UUID assigneeId,
            String assigneeName,
            String status,
            @Schema(description = "Past its date and still open")
            boolean overdue) {}

    @Schema(name = "CalendarDay")
    public record DayResponse(
            LocalDate date,
            boolean inMonth,
            boolean today,
            boolean weekend,
            List<EntryResponse> entries) {}

    @Schema(name = "CalendarResponse")
    public record CalendarResponse(
            @Schema(description = "The month this grid is for, as yyyy-MM-01")
            LocalDate month,
            LocalDate from,
            LocalDate to,
            @Schema(description = "Whole weeks, Monday first, covering the month")
            List<DayResponse> days,
            int totalEntries) {}
}
