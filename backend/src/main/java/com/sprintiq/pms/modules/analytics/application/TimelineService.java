package com.sprintiq.pms.modules.analytics.application;

import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.modules.analytics.web.dto.TimelineDtos.*;
import com.sprintiq.pms.modules.backlog.application.EpicService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.EpicResponse;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.projects.application.MilestoneService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.MilestoneResponse;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.SprintResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The delivery timeline, assembled rather than stored.
 *
 * <p>Every date on this screen already exists somewhere: an epic has a start and a
 * target, a sprint has a window, a milestone has a due date, and a story inherits the
 * window of the sprint it is committed to. Adding a timeline table would create a
 * second copy of all of it, and the second copy is always the one that is wrong.</p>
 *
 * <p>Where a bar's dates are <i>inferred</i> the response says so. A reader who cannot
 * tell a stated date from a guessed one will treat both as commitments, which is how a
 * Gantt chart becomes a source of arguments.</p>
 */
@Service
@RequiredArgsConstructor
public class TimelineService {

    private final EpicService epicService;
    private final StoryService storyService;
    private final SprintService sprintService;
    private final MilestoneService milestoneService;

    /**
     * Everything datable in a project, grouped by epic.
     *
     * <p>The window defaults to the span of the work itself rather than to a fixed
     * number of weeks: a project running to next March should show next March, and a
     * two-sprint project should not be drawn as a sliver at the left of a year.</p>
     */
    @Transactional(readOnly = true)
    public TimelineResponse timeline(UUID projectId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();

        List<EpicResponse> epics = epicService
                .search(projectId, null, null, null, unpaged()).content();
        List<StoryResponse> stories = storyService
                .search(projectId, null, null, null, null, null, null, false, unpaged()).content();
        List<SprintResponse> sprints = sprintService.search(projectId, null, unpaged()).content();
        List<MilestoneResponse> milestones = milestoneService.list(projectId);

        Map<UUID, SprintResponse> sprintsById = sprints.stream()
                .collect(Collectors.toMap(SprintResponse::id, sprint -> sprint));

        // Stories first: an epic with no dates of its own is dated by its children, so
        // the rows have to exist before the group bar can be built.
        Map<UUID, List<BarResponse>> rowsByEpic = new LinkedHashMap<>();
        int undated = 0;
        for (StoryResponse story : stories) {
            BarResponse bar = toBar(story, sprintsById.get(story.sprintId()), today);
            if (bar == null) {
                undated++;
                continue;
            }
            rowsByEpic.computeIfAbsent(story.epicId(), key -> new ArrayList<>()).add(bar);
        }

        List<GroupResponse> groups = new ArrayList<>();
        for (EpicResponse epic : epics) {
            List<BarResponse> rows = rowsByEpic.getOrDefault(epic.id(), List.of());
            BarResponse bar = toBar(epic, rows, today);
            if (bar == null) {
                undated++;
                continue;
            }
            groups.add(new GroupResponse(bar, rows));
        }

        // Stories with no epic still belong on the timeline; hiding them is how work
        // disappears from a plan. They gather under a group with no bar of its own.
        List<BarResponse> orphans = rowsByEpic.getOrDefault(null, List.of());
        if (!orphans.isEmpty()) {
            groups.add(new GroupResponse(
                    new BarResponse(null, null, "Not in an epic", BarKind.EPIC,
                            earliest(orphans), latest(orphans), DateSource.DERIVED,
                            null, null, null, null, false, false),
                    orphans));
        }

        List<BarResponse> milestoneBars = milestones.stream()
                .filter(milestone -> milestone.dueDate() != null)
                .map(milestone -> new BarResponse(milestone.id(), null, milestone.name(),
                        BarKind.MILESTONE, milestone.dueDate(), milestone.dueDate(),
                        DateSource.EXPLICIT, null, milestone.status().name(), null, null,
                        false, milestone.overdue()))
                .toList();

        List<BarResponse> sprintBars = sprints.stream()
                .filter(sprint -> sprint.startDate() != null && sprint.endDate() != null)
                .map(sprint -> new BarResponse(sprint.id(), null, sprint.name(), BarKind.SPRINT,
                        sprint.startDate(), sprint.endDate(), DateSource.EXPLICIT,
                        sprint.completionRate(), sprint.status().name(), null, null, false,
                        false))
                .toList();

        LocalDate windowFrom = from != null ? from : windowStart(groups, milestoneBars, sprintBars, today);
        LocalDate windowTo = to != null ? to : windowEnd(groups, milestoneBars, sprintBars, today);

        return new TimelineResponse(windowFrom, windowTo, today,
                weeksBetween(windowFrom, windowTo), groups, milestoneBars, sprintBars, undated);
    }

    // ------------------------------------------------------------------ internals

    private static PageQuery unpaged() {
        PageQuery query = new PageQuery();
        query.setSize(PageQuery.MAX_PAGE_SIZE);
        return query;
    }

    /**
     * A story's bar.
     *
     * <p>Three sources, in descending order of how much they can be trusted: the sprint
     * it is committed to (a real window somebody agreed), its due date (an end with no
     * start, drawn as a marker), or nothing at all — in which case it is honestly
     * excluded and counted rather than given invented dates.</p>
     */
    private static BarResponse toBar(StoryResponse story, SprintResponse sprint, LocalDate today) {
        LocalDate start;
        LocalDate end;
        DateSource source;

        if (sprint != null && sprint.startDate() != null && sprint.endDate() != null) {
            start = sprint.startDate();
            end = sprint.endDate();
            source = DateSource.SPRINT;
        } else if (story.dueDate() != null) {
            start = story.dueDate();
            end = story.dueDate();
            source = DateSource.DUE_DATE_ONLY;
        } else {
            return null;
        }

        boolean done = story.status() == StoryStatus.DONE;
        return new BarResponse(story.id(), story.itemKey(), story.title(), BarKind.STORY,
                start, end, source,
                done ? BigDecimal.valueOf(100) : BigDecimal.ZERO,
                story.status().name(), story.assigneeId(), story.assigneeName(),
                story.blocked(), !done && end.isBefore(today));
    }

    /**
     * An epic's bar. Uses its own dates when it has them, and falls back to the span of
     * its stories — marked DERIVED, because an inferred date is not a commitment.
     */
    private static BarResponse toBar(EpicResponse epic, List<BarResponse> rows, LocalDate today) {
        LocalDate start = epic.startDate();
        LocalDate end = epic.targetDate();
        DateSource source = DateSource.EXPLICIT;

        if (start == null || end == null) {
            if (rows.isEmpty()) {
                return null;
            }
            start = start != null ? start : earliest(rows);
            end = end != null ? end : latest(rows);
            source = DateSource.DERIVED;
        }

        BigDecimal progress = epic.storyCount() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(epic.completedStoryCount())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(epic.storyCount()), 2, RoundingMode.HALF_UP);

        boolean complete = epic.storyCount() > 0 && epic.completedStoryCount() == epic.storyCount();
        return new BarResponse(epic.id(), epic.itemKey(), epic.title(), BarKind.EPIC,
                start, end, source, progress, epic.status().name(), null, null,
                false, !complete && end.isBefore(today));
    }

    private static LocalDate earliest(List<BarResponse> bars) {
        return bars.stream().map(BarResponse::start).min(LocalDate::compareTo).orElseThrow();
    }

    private static LocalDate latest(List<BarResponse> bars) {
        return bars.stream().map(BarResponse::end).max(LocalDate::compareTo).orElseThrow();
    }

    private static LocalDate windowStart(List<GroupResponse> groups, List<BarResponse> milestones,
                                         List<BarResponse> sprints, LocalDate today) {
        // Snapped to a Monday so the week columns line up with how people talk about
        // weeks, rather than starting mid-week on whatever date the earliest bar has.
        LocalDate earliest = Stream(groups, milestones, sprints)
                .map(BarResponse::start)
                .min(LocalDate::compareTo)
                .orElse(today);
        LocalDate anchor = earliest.isBefore(today) ? earliest : today;
        return anchor.minusDays(anchor.getDayOfWeek().getValue() - 1L);
    }

    private static LocalDate windowEnd(List<GroupResponse> groups, List<BarResponse> milestones,
                                       List<BarResponse> sprints, LocalDate today) {
        LocalDate latest = Stream(groups, milestones, sprints)
                .map(BarResponse::end)
                .max(LocalDate::compareTo)
                .orElse(today.plusWeeks(8));
        // At least a fortnight beyond today, so "now" is never pinned to the right edge
        // with nothing visible after it.
        return latest.isAfter(today.plusWeeks(2)) ? latest : today.plusWeeks(2);
    }

    /** Every bar on the chart, flattened. Named for what it is rather than how it works. */
    private static java.util.stream.Stream<BarResponse> Stream(
            List<GroupResponse> groups, List<BarResponse> milestones, List<BarResponse> sprints) {
        return java.util.stream.Stream.of(
                        groups.stream().map(GroupResponse::bar),
                        groups.stream().flatMap(group -> group.rows().stream()),
                        milestones.stream(),
                        sprints.stream())
                .flatMap(stream -> stream);
    }

    private static List<WeekResponse> weeksBetween(LocalDate from, LocalDate to) {
        List<WeekResponse> weeks = new ArrayList<>();
        LocalDate cursor = from;
        // Bounded so a corrupt date range cannot spin here: two years of weeks is far
        // past the point where a Gantt chart is readable anyway.
        while (!cursor.isAfter(to) && weeks.size() < 104) {
            weeks.add(new WeekResponse(cursor, label(cursor)));
            cursor = cursor.plusWeeks(1);
        }
        return weeks;
    }

    /** "Aug W2" — the month, then which week of it this is. */
    private static String label(LocalDate weekStart) {
        int weekOfMonth = (weekStart.getDayOfMonth() - 1) / 7 + 1;
        return "%s W%d".formatted(
                weekStart.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), weekOfMonth);
    }

    /** Whole days between two dates, for the client's bar arithmetic. */
    public static long days(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to);
    }
}
