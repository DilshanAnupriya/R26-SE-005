package com.sprintiq.pms.modules.analytics.application;

import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.modules.analytics.web.dto.ReportDtos.*;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.projects.application.ProjectLookupService;
import com.sprintiq.pms.modules.sprints.application.SprintMetricsService;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.BurndownResponse;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.SprintResponse;
import com.sprintiq.pms.modules.tasks.application.BoardService;
import com.sprintiq.pms.modules.tasks.application.TimeTrackingService;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.WorkloadEntryResponse;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * The reporting layer.
 *
 * <p>Assembled from the delivery modules' published services rather than from their
 * tables. Reporting is the classic place where module boundaries quietly dissolve — one
 * join across two schemas is always easier than the alternative — and the result is a
 * monolith that cannot be decomposed because the reports know everything.</p>
 *
 * <p>Percentiles, not averages, wherever a distribution has a tail. A mean cycle time of
 * four days hides the story that one item in ten takes three weeks, and that item is the
 * one the team is being asked about.</p>
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /** Sprints included in a trend chart unless the caller asks for more. */
    private static final int DEFAULT_SPRINT_WINDOW = 12;

    private final SprintService sprintService;
    private final SprintMetricsService sprintMetrics;
    private final StoryService storyService;
    private final BoardService boardService;
    private final TimeTrackingService timeTracking;
    private final ProjectLookupService projectLookup;
    private final UserService userService;

    // ------------------------------------------------------------------ velocity

    @Transactional(readOnly = true)
    public VelocityReportResponse velocity(UUID projectId, Integer sprintCount) {
        projectLookup.require(projectId);
        List<SprintResponse> sprints = recentSprints(projectId, sprintCount);

        List<VelocityPoint> points = sprints.stream()
                .map(sprint -> new VelocityPoint(sprint.id(), sprint.name(), sprint.sequenceNo(),
                        sprint.committedPoints(), sprint.completedPoints(), sprint.addedPoints(),
                        sprint.removedPoints(), sprint.completionRate(), sprint.endDate()))
                .toList();

        List<BigDecimal> completed = points.stream().map(VelocityPoint::completedPoints).toList();
        return new VelocityReportResponse(projectId, points, mean(completed), stdDev(completed),
                mean(points.stream().map(VelocityPoint::completionRate).toList()), "points");
    }

    @Transactional(readOnly = true)
    public BurnReportResponse burndown(UUID sprintId) {
        BurndownResponse burndown = sprintMetrics.burndown(sprintId);
        return new BurnReportResponse(burndown.sprintId(), burndown.startDate(), burndown.endDate(),
                burndown.committedPoints(), burndown.completedPoints(),
                burndown.points().stream()
                        .map(point -> new BurnPoint(point.date(), point.idealRemaining(),
                                point.actualRemaining(), point.completed(), point.scopeChange()))
                        .toList(),
                "points");
    }

    /**
     * Burn-up: the same snapshots read the other way round.
     *
     * <p>Burn-up is the chart to look at when scope is moving, because the total line and
     * the done line are drawn separately — a burndown that flattens looks identical
     * whether the team stopped delivering or the scope grew underneath them.</p>
     */
    @Transactional(readOnly = true)
    public BurnReportResponse burnup(UUID sprintId) {
        BurndownResponse burndown = sprintMetrics.burndown(sprintId);
        List<BurnPoint> points = burndown.points().stream()
                .map(point -> new BurnPoint(point.date(),
                        burndown.committedPoints().add(
                                point.scopeChange() == null ? BigDecimal.ZERO : point.scopeChange()),
                        point.completed(), point.completed(), point.scopeChange()))
                .toList();
        return new BurnReportResponse(burndown.sprintId(), burndown.startDate(), burndown.endDate(),
                burndown.committedPoints(), burndown.completedPoints(), points, "points");
    }

    /**
     * Cumulative flow, derived from item timestamps.
     *
     * <p>Three bands rather than one per workflow column: {@code startedAt} and
     * {@code completedAt} are the only per-item timestamps recorded, so To Do / In
     * Progress / Done is genuinely reconstructable and a per-column history is not.
     * Inventing intermediate transitions from current state would produce a chart that
     * looked precise and was fiction.</p>
     */
    @Transactional(readOnly = true)
    public CfdResponse cumulativeFlow(UUID projectId, Integer days) {
        projectLookup.require(projectId);
        int window = days == null || days <= 0 ? 30 : Math.min(days, 180);
        List<StoryResponse> stories = allStories(projectId);

        List<LocalDate> dates = new ArrayList<>(window);
        LocalDate today = LocalDate.now();
        for (int offset = window - 1; offset >= 0; offset--) {
            dates.add(today.minusDays(offset));
        }

        List<Long> todo = new ArrayList<>(window);
        List<Long> inProgress = new ArrayList<>(window);
        List<Long> done = new ArrayList<>(window);

        for (LocalDate date : dates) {
            Instant endOfDay = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            long created = 0;
            long started = 0;
            long finished = 0;
            for (StoryResponse story : stories) {
                if (story.createdAt() == null || story.createdAt().isAfter(endOfDay)) continue;
                created++;
                if (story.startedAt() != null && !story.startedAt().isAfter(endOfDay)) started++;
                if (story.completedAt() != null && !story.completedAt().isAfter(endOfDay)) finished++;
            }
            todo.add(created - started);
            inProgress.add(started - finished);
            done.add(finished);
        }

        return new CfdResponse(projectId, dates, List.of(
                new CfdBand("To do", todo),
                new CfdBand("In progress", inProgress),
                new CfdBand("Done", done)));
    }

    // ------------------------------------------------------- cycle and lead time

    /** Started to done — how long work takes once it is actually being worked on. */
    @Transactional(readOnly = true)
    public DurationDistributionResponse cycleTime(UUID projectId) {
        return distribution(projectId, "cycleTime",
                story -> story.startedAt() == null || story.completedAt() == null
                        ? null
                        : Duration.between(story.startedAt(), story.completedAt()));
    }

    /** Created to done — how long a request waits, which is what a stakeholder feels. */
    @Transactional(readOnly = true)
    public DurationDistributionResponse leadTime(UUID projectId) {
        return distribution(projectId, "leadTime",
                story -> story.createdAt() == null || story.completedAt() == null
                        ? null
                        : Duration.between(story.createdAt(), story.completedAt()));
    }

    // ----------------------------------------------------------------- the team

    /**
     * Per-person delivery figures.
     *
     * <p>Returned unsorted and without a rank, deliberately. Story points are a planning
     * unit, not an output measure — they are estimated by the team, for the team — and a
     * league table built on them teaches people to inflate their estimates. The note
     * field says so in the payload, where whoever builds the next screen will read it.</p>
     */
    @Transactional(readOnly = true)
    public ProductivityReportResponse productivity(UUID projectId, UUID sprintId) {
        projectLookup.require(projectId);
        List<StoryResponse> stories = allStories(projectId).stream()
                .filter(story -> sprintId == null || sprintId.equals(story.sprintId()))
                .toList();

        Map<UUID, List<StoryResponse>> byAssignee = new LinkedHashMap<>();
        for (StoryResponse story : stories) {
            if (story.assigneeId() == null) continue;
            byAssignee.computeIfAbsent(story.assigneeId(), key -> new ArrayList<>()).add(story);
        }

        Map<UUID, UserSummary> people = userService.summariesFor(byAssignee.keySet());
        List<ProductivityRow> rows = new ArrayList<>(byAssignee.size());

        byAssignee.forEach((userId, owned) -> {
            List<StoryResponse> completed = owned.stream()
                    .filter(story -> story.status() == StoryStatus.DONE)
                    .toList();
            BigDecimal points = completed.stream()
                    .map(story -> story.storyPoints() == null ? BigDecimal.ZERO : story.storyPoints())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<BigDecimal> cycleDays = completed.stream()
                    .filter(story -> story.startedAt() != null && story.completedAt() != null)
                    .map(story -> days(Duration.between(story.startedAt(), story.completedAt())))
                    .toList();
            long reopened = owned.stream().mapToLong(StoryResponse::reopenedCount).sum();

            UserSummary summary = people.get(userId);
            rows.add(new ProductivityRow(userId,
                    summary == null ? "Unknown" : summary.fullName(),
                    summary == null ? null : summary.avatarUrl(),
                    completed.size(), points,
                    timeTracking.minutesLoggedBy(userId, projectId, sprintId),
                    mean(cycleDays), reopened));
        });

        return new ProductivityReportResponse(projectId, sprintId, rows,
                "Points are a planning unit, not an output measure. These figures are for "
                        + "spotting imbalance, not for comparing people.");
    }

    @Transactional(readOnly = true)
    public WorkloadBalanceResponse workloadBalance(UUID projectId) {
        projectLookup.require(projectId);
        List<WorkloadEntryResponse> entries = boardService.workload(projectId);

        List<WorkloadRow> rows = entries.stream()
                .map(entry -> new WorkloadRow(entry.userId(), entry.fullName(), entry.avatarUrl(),
                        entry.openTaskCount(), entry.blockedTaskCount(), entry.estimatedMinutes(),
                        entry.loadPct(), entry.overAllocated()))
                .toList();

        List<BigDecimal> loads = rows.stream().map(WorkloadRow::loadPct).toList();
        BigDecimal highest = loads.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal lowest = loads.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);

        long unassigned = allStories(projectId).stream()
                .filter(story -> story.assigneeId() == null && story.status() != StoryStatus.DONE)
                .count();

        return new WorkloadBalanceResponse(projectId, rows, mean(loads),
                highest.subtract(lowest), unassigned);
    }

    @Transactional(readOnly = true)
    public CompletionRateResponse completionRate(UUID projectId) {
        projectLookup.require(projectId);
        List<StoryResponse> stories = allStories(projectId);
        long completed = stories.stream()
                .filter(story -> story.status() == StoryStatus.DONE).count();

        BigDecimal totalPoints = stories.stream()
                .map(story -> story.storyPoints() == null ? BigDecimal.ZERO : story.storyPoints())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal completedPoints = stories.stream()
                .filter(story -> story.status() == StoryStatus.DONE)
                .map(story -> story.storyPoints() == null ? BigDecimal.ZERO : story.storyPoints())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CompletionRateResponse(projectId, stories.size(), completed,
                percentage(completed, stories.size()), percentage(completedPoints, totalPoints),
                velocity(projectId, DEFAULT_SPRINT_WINDOW).sprints());
    }

    // ------------------------------------------------------------------ internals

    private List<SprintResponse> recentSprints(UUID projectId, Integer count) {
        PageQuery query = new PageQuery();
        query.setSize(Math.min(count == null || count <= 0 ? DEFAULT_SPRINT_WINDOW : count,
                PageQuery.MAX_PAGE_SIZE));
        query.setSort(List.of("sequenceNo,asc"));
        return sprintService.search(projectId, null, query).content();
    }

    /**
     * Every story in a project.
     *
     * <p>Capped at one page of the maximum size. Reporting over a project with more than
     * a hundred stories per request would need a materialised rollup, and returning a
     * silently truncated chart is better than an unbounded query that takes the database
     * with it — but the cap is the reason the roadmap has a reporting-snapshot table on
     * it.</p>
     */
    private List<StoryResponse> allStories(UUID projectId) {
        PageQuery query = new PageQuery();
        query.setSize(PageQuery.MAX_PAGE_SIZE);
        return storyService.search(projectId, null, null, null, null, null, null, false, query)
                .content();
    }

    private DurationDistributionResponse distribution(UUID projectId, String metric,
                                                      java.util.function.Function<StoryResponse,
                                                              Duration> extractor) {
        projectLookup.require(projectId);
        List<DurationSample> samples = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();

        for (StoryResponse story : allStories(projectId)) {
            Duration duration = extractor.apply(story);
            if (duration == null || duration.isNegative()) continue;
            BigDecimal asDays = days(duration);
            values.add(asDays);
            samples.add(new DurationSample(story.id(), story.itemKey(), story.title(), asDays,
                    story.completedAt()));
        }

        List<BigDecimal> sorted = values.stream().sorted().toList();
        samples.sort(Comparator.comparing(DurationSample::days).reversed());

        return new DurationDistributionResponse(projectId, metric, sorted.size(),
                percentile(sorted, 50), percentile(sorted, 85), percentile(sorted, 95),
                mean(sorted), sorted.isEmpty() ? BigDecimal.ZERO : sorted.get(sorted.size() - 1),
                samples.stream().limit(20).toList(), "days");
    }

    private BigDecimal days(Duration duration) {
        return BigDecimal.valueOf(duration.toMinutes())
                .divide(BigDecimal.valueOf(60 * 24), 2, RoundingMode.HALF_UP);
    }

    /** Nearest-rank percentile — no interpolation between two real observations. */
    private BigDecimal percentile(List<BigDecimal> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(rank - 1, sorted.size() - 1)));
    }

    private BigDecimal mean(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return present.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(present.size()), 2, RoundingMode.HALF_UP);
    }

    /** Population standard deviation — how predictable a team's velocity is. */
    private BigDecimal stdDev(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(Objects::nonNull).toList();
        if (present.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal average = mean(present);
        BigDecimal variance = present.stream()
                .map(value -> value.subtract(average).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(present.size()), 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentage(long part, long total) {
        return total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(total), 1,
                RoundingMode.HALF_UP);
    }

    private BigDecimal percentage(BigDecimal part, BigDecimal total) {
        return total.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
}
