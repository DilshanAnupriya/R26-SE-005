package com.sprintiq.pms.modules.analytics.application;

import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.modules.analytics.web.dto.CalendarDtos.*;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.goals.application.GoalService;
import com.sprintiq.pms.modules.goals.web.dto.GoalDtos.GoalResponse;
import com.sprintiq.pms.modules.projects.application.MilestoneService;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.application.ProjectSummary;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.MilestoneResponse;
import com.sprintiq.pms.modules.roadmap.application.RoadmapService;
import com.sprintiq.pms.modules.roadmap.web.dto.RoadmapDtos.InitiativeResponse;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.SprintResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * Everything the system already knows is due, on a month grid.
 *
 * <p>Assembled from the modules' published services, exactly like the timeline and for
 * the same reason: a calendar table would be a second copy of dates that already exist,
 * and it would be the copy that drifts. The cost is that this calendar shows
 * commitments rather than meetings — the screen says so, because a reader who does not
 * know that reads an empty week as "nothing on" rather than "nothing tracked here".</p>
 */
@Service
@RequiredArgsConstructor
public class CalendarService {

    private final SprintService sprintService;
    private final StoryService storyService;
    private final MilestoneService milestoneService;
    private final ProjectService projectService;
    private final RoadmapService roadmapService;
    private final GoalService goalService;

    /**
     * The grid for a month, padded to whole weeks.
     *
     * <p>Whole weeks because a month that starts on a Thursday still has a Monday, and
     * a calendar that begins mid-row is one nobody can scan. The padding days are
     * marked so the client can dim them rather than having to recompute the month.</p>
     */
    @Transactional(readOnly = true)
    public CalendarResponse month(LocalDate month, UUID projectId) {
        LocalDate first = (month == null ? LocalDate.now() : month).withDayOfMonth(1);
        LocalDate from = first.minusDays(first.getDayOfWeek().getValue() - 1L);
        LocalDate lastOfMonth = first.plusMonths(1).minusDays(1);
        LocalDate to = lastOfMonth.plusDays(7L - lastOfMonth.getDayOfWeek().getValue());

        List<ProjectSummary> projects = projectsInScope(projectId);
        Map<UUID, ProjectSummary> byId = new HashMap<>();
        projects.forEach(project -> byId.put(project.id(), project));

        LocalDate today = LocalDate.now();
        List<EntryResponse> entries = new ArrayList<>();

        for (ProjectSummary project : projects) {
            entries.addAll(sprintEntries(project, from, to));
            entries.addAll(milestoneEntries(project, from, to, today));
            entries.addAll(storyEntries(project, from, to, today));
        }
        entries.addAll(initiativeEntries(byId, from, to, today));
        entries.addAll(goalEntries(byId, from, to, today));

        Map<LocalDate, List<EntryResponse>> byDate = new HashMap<>();
        for (EntryResponse entry : entries) {
            byDate.computeIfAbsent(entry.date(), key -> new ArrayList<>()).add(entry);
        }

        List<DayResponse> days = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            days.add(new DayResponse(
                    cursor,
                    cursor.getMonth() == first.getMonth() && cursor.getYear() == first.getYear(),
                    cursor.equals(today),
                    cursor.getDayOfWeek() == DayOfWeek.SATURDAY
                            || cursor.getDayOfWeek() == DayOfWeek.SUNDAY,
                    byDate.getOrDefault(cursor, List.of())));
        }

        return new CalendarResponse(first, from, to, days, entries.size());
    }

    // ------------------------------------------------------------------ internals

    private List<ProjectSummary> projectsInScope(UUID projectId) {
        if (projectId != null) {
            ProjectSummary only = projectService.summariesFor(List.of(projectId)).get(projectId);
            return only == null ? List.of() : List.of(only);
        }
        // Every project the caller can see. A calendar scoped to one project answers
        // "what is due on this project"; the useful question is "what is due".
        return projectService.search(null, null, null, null, unpaged()).content().stream()
                .map(project -> new ProjectSummary(project.id(), project.projectKey(), project.name()))
                .toList();
    }

    private static PageQuery unpaged() {
        PageQuery query = new PageQuery();
        query.setSize(PageQuery.MAX_PAGE_SIZE);
        return query;
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    /**
     * A sprint contributes two entries, not one span.
     *
     * <p>A calendar answers "what happens on this day", and the two days that matter
     * about a sprint are the one it starts and the one it ends. Drawing the fourteen
     * days between them fills every cell and tells nobody anything.</p>
     */
    private List<EntryResponse> sprintEntries(ProjectSummary project, LocalDate from, LocalDate to) {
        List<EntryResponse> entries = new ArrayList<>();
        for (SprintResponse sprint : sprintService.search(project.id(), null, unpaged()).content()) {
            if (within(sprint.startDate(), from, to)) {
                entries.add(new EntryResponse(sprint.id(), EntryKind.SPRINT_START,
                        sprint.startDate(), sprint.name() + " starts", null, project.id(),
                        project.projectKey(), null, null, sprint.status().name(), false));
            }
            if (within(sprint.endDate(), from, to)) {
                entries.add(new EntryResponse(sprint.id(), EntryKind.SPRINT_END,
                        sprint.endDate(), sprint.name() + " ends", null, project.id(),
                        project.projectKey(), null, null, sprint.status().name(), false));
            }
        }
        return entries;
    }

    private List<EntryResponse> milestoneEntries(ProjectSummary project, LocalDate from,
                                                 LocalDate to, LocalDate today) {
        List<EntryResponse> entries = new ArrayList<>();
        for (MilestoneResponse milestone : milestoneService.list(project.id())) {
            if (within(milestone.dueDate(), from, to)) {
                entries.add(new EntryResponse(milestone.id(), EntryKind.MILESTONE,
                        milestone.dueDate(), milestone.name(), null, project.id(),
                        project.projectKey(), null, null, milestone.status().name(),
                        milestone.overdue()));
            }
        }
        return entries;
    }

    private List<EntryResponse> storyEntries(ProjectSummary project, LocalDate from,
                                             LocalDate to, LocalDate today) {
        List<EntryResponse> entries = new ArrayList<>();
        for (StoryResponse story : storyService
                .search(project.id(), null, null, null, null, null, null, false, unpaged())
                .content()) {
            if (!within(story.dueDate(), from, to)) {
                continue;
            }
            boolean done = story.status() == StoryStatus.DONE;
            entries.add(new EntryResponse(story.id(), EntryKind.STORY_DUE, story.dueDate(),
                    story.title(), story.itemKey(), project.id(), project.projectKey(),
                    story.assigneeId(), story.assigneeName(), story.status().name(),
                    !done && story.dueDate().isBefore(today)));
        }
        return entries;
    }

    private List<EntryResponse> initiativeEntries(Map<UUID, ProjectSummary> projects,
                                                  LocalDate from, LocalDate to, LocalDate today) {
        List<EntryResponse> entries = new ArrayList<>();
        for (var lane : roadmapService.roadmap(null, null, from, to).lanes()) {
            for (InitiativeResponse initiative : lane.initiatives()) {
                if (!within(initiative.targetDate(), from, to)) {
                    continue;
                }
                ProjectSummary project = initiative.projectId() == null
                        ? null : projects.get(initiative.projectId());
                entries.add(new EntryResponse(initiative.id(), EntryKind.INITIATIVE_TARGET,
                        initiative.targetDate(), initiative.title(), null,
                        initiative.projectId(), project == null ? null : project.projectKey(),
                        initiative.ownerUserId(), initiative.ownerName(),
                        initiative.status().name(), initiative.overdue()));
            }
        }
        return entries;
    }

    private List<EntryResponse> goalEntries(Map<UUID, ProjectSummary> projects,
                                            LocalDate from, LocalDate to, LocalDate today) {
        List<EntryResponse> entries = new ArrayList<>();
        for (GoalResponse goal : goalService.search(null, null, null, null, null, unpaged()).content()) {
            if (!within(goal.targetDate(), from, to)) {
                continue;
            }
            ProjectSummary project = goal.projectId() == null ? null : projects.get(goal.projectId());
            entries.add(new EntryResponse(goal.id(), EntryKind.GOAL_TARGET, goal.targetDate(),
                    goal.title(), null, goal.projectId(),
                    project == null ? null : project.projectKey(),
                    goal.ownerUserId(), goal.ownerName(), goal.status().name(), goal.overdue()));
        }
        return entries;
    }
}
