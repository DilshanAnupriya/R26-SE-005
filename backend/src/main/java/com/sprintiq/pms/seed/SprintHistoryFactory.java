package com.sprintiq.pms.seed;

import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.CreateStoryRequest;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.UpdateStoryStatusRequest;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintMetric;
import com.sprintiq.pms.modules.sprints.domain.SprintMetricRepository;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.AddSprintItemsRequest;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.CreateSprintRequest;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.SprintResponse;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manufactures the closed sprints a delivery chart needs before it says anything.
 *
 * <p>Every trend view in the product — the velocity bar chart, its consistency figure,
 * the burndown, cycle and lead time, the velocity model's rolling window — reads
 * finished sprints. A project with one closed sprint draws all of them, and every one
 * is a single point: technically correct and completely uninformative. This builds the
 * history those views were designed against.</p>
 *
 * <h2>Why this cannot go through the services alone</h2>
 *
 * <p>The seeder's rule is to create everything through the application services, and it
 * is a good rule: a fixture that writes rows directly can reach states the product
 * cannot, and then the first bug report is about the fixture. History is the one thing
 * that rule cannot express. Every write path in the product stamps {@code Instant.now()}
 * — {@code Story.transitionTo} sets {@code completedAt} to now, {@code SprintService}
 * snapshots to {@link LocalDate#now()}, {@code created_at} is mapped
 * {@code updatable = false} so JPA will not rewrite it at all. Driving those services in
 * a loop produces ten sprints that all began and ended this afternoon, which is not
 * history; it is ten copies of today.</p>
 *
 * <p>So the split is deliberate: structure through the services, where they enforce the
 * invariants worth enforcing — a sprint's board, a story's workflow state, the item
 * links — and the clock through direct writes, because the clock is exactly what the
 * services are built to control. Each such write is marked below.</p>
 *
 * <h2>What the numbers are</h2>
 *
 * <p>Deterministic, not random. A fixture that produces a different chart on every run
 * cannot be used to reproduce anything, and "the velocity chart looks wrong" is not a
 * report anybody can act on if the data moved. The variation comes from a fixed cycle
 * of completion ratios, so the same project always gets the same history.</p>
 */
@Slf4j
@Component
@Profile("!prod")
@RequiredArgsConstructor
public class SprintHistoryFactory {

    /**
     * Delivered-over-committed, sprint by sprint, oldest first.
     *
     * <p>Chosen to make the charts worth opening rather than to flatter the team. There
     * is a strong start, a bad sprint that undershoots by a third, a recovery, and a
     * late wobble — which is what gives the consistency figure a standard deviation to
     * report and the comparison chart bars of different heights. A run of ten sprints
     * that all deliver exactly what they promised teaches a reader nothing, and no team
     * has ever produced one.</p>
     *
     * <p>Two sprints exceed 1.0. Delivering more than committed is real — it is what
     * pulling work forward from the backlog looks like — and a chart where the delivered
     * bar can never pass the committed one hides that case entirely.</p>
     *
     * <p>These are targets, not outcomes. Delivery is whole stories, so the realised
     * figure lands at or a little under the ratio depending on what fitted — which is
     * the same reason a real team rarely hits a round percentage.</p>
     */
    private static final double[] COMPLETION_RATIOS = {
            0.95, 0.68, 1.04, 0.88, 0.76, 1.00, 0.92, 0.61, 1.09, 0.86,
    };

    /** Commitment wobble around the project's baseline velocity, same cycle length. */
    private static final double[] COMMITMENT_FACTORS = {
            0.85, 1.00, 0.92, 1.15, 1.08, 0.96, 1.00, 1.20, 0.88, 1.04,
    };

    /**
     * Story sizes, cycled to fill a sprint's commitment.
     *
     * <p>Fibonacci, because that is the scale the estimator reports on: a backlog of 4s
     * and 6s would make every AI suggestion look wrong for reasons that have nothing to
     * do with the model.</p>
     *
     * <p>Small ones, and no 13. Sizes averaging five gave a twenty-point sprint four
     * items, and delivery is whole stories — so a sprint meant to land 68% skipped the
     * one item that would not fit and landed 35% instead. Six or seven smaller items
     * let the delivered total sit near its intended ratio, and it is what a two-week
     * sprint actually holds. A 13 in a 20-point sprint is not a story, it is the
     * sprint.</p>
     */
    private static final int[] STORY_POINTS = {2, 3, 5, 3, 8, 2, 5, 3, 2, 5};

    private final SprintService sprintService;
    private final StoryService storyService;
    private final SprintRepository sprintRepository;
    private final StoryRepository storyRepository;
    private final SprintMetricRepository metricRepository;
    private final JdbcTemplate jdbc;

    /**
     * @param sprintCount      how many closed sprints to lay down
     * @param sprintLengthDays calendar days per sprint, start to end inclusive
     * @param baseVelocity     the points this team commits to in a typical sprint
     * @param namePrefix       prepended to the sprint number, separator included:
     *                         "Sprint " gives "Sprint 7", "Sprint H" gives "Sprint H7"
     * @param firstSequenceNo  sequence number of the oldest sprint; the rest follow
     * @param endsBefore       the history stops the day before this date
     * @param assignees        rotated across stories; empty means unassigned
     * @param titles           rotated for story titles, so the backlog reads like work
     */
    public record Spec(int sprintCount,
                       int sprintLengthDays,
                       int baseVelocity,
                       String namePrefix,
                       int firstSequenceNo,
                       LocalDate endsBefore,
                       List<UUID> assignees,
                       List<String> titles) {}

    /**
     * Builds the history and returns the sprints it created, oldest first.
     *
     * <p>Not {@code @Transactional} at this level, for the same reason the seeder is
     * not: each sprint commits as it goes, so a failure on sprint seven leaves six
     * usable ones rather than nothing.</p>
     */
    public List<UUID> build(UUID projectId, Spec spec) {
        List<UUID> created = new ArrayList<>(spec.sprintCount());

        for (int index = 0; index < spec.sprintCount(); index++) {
            // Laid out backwards from the anchor so the newest seeded sprint ends the
            // day before whatever already exists, and the oldest is furthest back.
            int sprintsAfterThisOne = spec.sprintCount() - 1 - index;
            LocalDate end = spec.endsBefore().minusDays(1L + (long) sprintsAfterThisOne * spec.sprintLengthDays());
            LocalDate start = end.minusDays(spec.sprintLengthDays() - 1L);

            created.add(buildOne(projectId, spec, index, start, end));
        }
        return created;
    }

    /**
     * Deliberately not {@code @Transactional}. It is called from {@link #build} on
     * {@code this}, so the proxy would be bypassed and the annotation would be a
     * decoration that reads like a guarantee. Each service call and repository save
     * carries its own boundary instead, which is what leaves a partial run usable.
     */
    private UUID buildOne(UUID projectId, Spec spec, int index, LocalDate start, LocalDate end) {
        int cycle = index % COMPLETION_RATIOS.length;
        BigDecimal targetCommitment = BigDecimal.valueOf(
                Math.round(spec.baseVelocity() * COMMITMENT_FACTORS[cycle]));

        SprintResponse sprint = sprintService.create(new CreateSprintRequest(projectId,
                "%s%d".formatted(spec.namePrefix(), spec.firstSequenceNo() + index),
                "Deliver the committed scope", start, end, null, null, null, null));

        List<Sized> committedWork = writeStories(projectId, spec, index, targetCommitment, 0);
        addToSprint(sprint.id(), committedWork);
        BigDecimal committed = total(committedWork);

        double ratio = COMPLETION_RATIOS[cycle];
        BigDecimal wanted = committed.multiply(BigDecimal.valueOf(ratio));

        // Delivery is whole stories, never a points target. A team does not finish 68%
        // of a five-pointer, so the delivered figure is whatever set of items fits
        // under the ratio — which is why the seeded numbers are not round multiples.
        List<Sized> delivered = new ArrayList<>(takeWithin(committedWork, wanted));
        BigDecimal pulledIn = BigDecimal.ZERO;

        if (ratio > 1.0) {
            // Over-delivery is real, and it is not the team finishing more of what they
            // promised — there is no more of it. It is work pulled forward from the
            // backlog once the committed scope ran out. Modelling it as extra items
            // added mid-sprint is what makes the delivered bar able to pass the
            // committed one, and it books the difference as scope added, which is what
            // the M2 requirement-change model reads.
            List<Sized> extra = writeStories(projectId, spec, index,
                    wanted.subtract(committed), committedWork.size());
            addToSprint(sprint.id(), extra);
            delivered.addAll(extra);
            pulledIn = total(extra);
        }

        completeStories(delivered, start, end);
        // Unfinished work goes back to the backlog rather than being dragged forward,
        // mirroring SpilloverStrategy.BACKLOG — which is what the seeder's existing
        // completed sprint does, and the choice that keeps the next sprint honest.
        returnToBacklog(committedWork, delivered);

        BigDecimal completed = total(delivered);
        closeSprint(sprint.id(), start, end, committed, completed, pulledIn);
        writeDailyMetrics(sprint.id(), start, end, committed, completed);

        return sprint.id();
    }

    // ------------------------------------------------------------------- stories

    /** A story and the size it was written at, so totals need no second read. */
    private record Sized(UUID id, BigDecimal points) {}

    private List<Sized> writeStories(UUID projectId, Spec spec, int index, BigDecimal target,
                                     int slotOffset) {
        List<Sized> stories = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        if (target.signum() <= 0) return stories;
        int slot = index * 3 + slotOffset;   // offset per sprint, so consecutive sprints differ
        int sprintNumber = spec.firstSequenceNo() + index;

        while (running.compareTo(target) < 0) {
            BigDecimal points = BigDecimal.valueOf(STORY_POINTS[slot % STORY_POINTS.length]);
            String title = spec.titles().get(slot % spec.titles().size());
            UUID assignee = spec.assignees().isEmpty()
                    ? null
                    : spec.assignees().get(slot % spec.assignees().size());

            // Arguments are positional and this record has seventeen of them, so they
            // are named here rather than counted. The existing seeder's helper had the
            // assignee landing in the sprintId slot, which no reader would spot and no
            // compiler could — both are UUID.
            CreateStoryRequest request = new CreateStoryRequest(
                    projectId,                                   // projectId
                    null,                                        // epicId
                    null,                                        // requirementId
                    "%s (%s%d)".formatted(title, spec.namePrefix(), sprintNumber),
                    // A description, because an empty one puts every estimate on the
                    // model's "unspecified work" branch and the demo then shows a
                    // backlog of identical suggestions.
                    "Seeded delivery history. " + title + ".",   // description
                    null,                                        // acceptanceCriteria
                    null,                                        // type
                    null,                                        // priority
                    null,                                        // severity
                    points,                                      // storyPoints
                    null,                                        // businessValue
                    assignee,                                    // assigneeId
                    null,                                        // sprintId — added below
                    null,                                        // dueDate
                    null,                                        // readyChecklist
                    null,                                        // doneChecklist
                    null);                                       // labelIds

            stories.add(new Sized(storyService.create(request).id(), points));
            running = running.add(points);
            slot++;
        }
        return stories;
    }

    /**
     * The stories that fit under the target, in order.
     *
     * <p>Takes a story only when it does not push the total past the target. The
     * obvious version — take until the running total reaches the target — always
     * includes the item that crosses it, and with a backlog of 5s, 8s and 13s that
     * means it takes everything: a 20-point sprint asked to deliver 68% would grab all
     * three stories and report 100%. Every seeded sprint came out delivering exactly
     * what it committed, which is the one thing no velocity chart should ever show.</p>
     */
    private List<Sized> takeWithin(List<Sized> stories, BigDecimal target) {
        List<Sized> taken = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (Sized story : stories) {
            BigDecimal next = running.add(story.points());
            if (next.compareTo(target) > 0) continue;   // skip, a later smaller one may fit
            taken.add(story);
            running = next;
        }
        return taken;
    }

    private void addToSprint(UUID sprintId, List<Sized> stories) {
        if (stories.isEmpty()) return;
        sprintService.addItems(sprintId,
                new AddSprintItemsRequest(stories.stream().map(Sized::id).toList()));
    }

    private BigDecimal total(List<Sized> stories) {
        return stories.stream().map(Sized::points).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Marks the delivered work done, then moves its clock back into the sprint.
     *
     * <p>Through {@code changeStatus} rather than by setting the column, so the story
     * lands in the project's Done workflow state and the board and the cumulative flow
     * chart agree with the status. The service then stamps started and completed as
     * now, which is the part that has to be undone: cycle time reads started→completed
     * and lead time reads created→completed, so a story finished "now" inside a sprint
     * that ended in June produces a three-month tail on every distribution.</p>
     */
    private void completeStories(List<Sized> delivered, LocalDate start, LocalDate end) {
        int span = Math.max(1, (int) (end.toEpochDay() - start.toEpochDay()));

        for (int i = 0; i < delivered.size(); i++) {
            UUID storyId = delivered.get(i).id();
            storyService.changeStatus(storyId, new UpdateStoryStatusRequest(StoryStatus.DONE, null));

            // Spread across the sprint rather than all on the last day: work that all
            // completes on one date gives a burndown cliff and a cycle-time
            // distribution with no spread, which is the shape of a fixture, not a team.
            int done = 1 + (int) Math.round((double) (i + 1) / delivered.size() * (span - 1));
            LocalDate completedOn = start.plusDays(done);
            // Picked up a few days before it finished, and raised before the sprint
            // began — a story invented on the day it starts has no lead time at all.
            LocalDate startedOn = completedOn.minusDays(Math.min(done, 2L + (i % 4)));
            LocalDate createdOn = start.minusDays(3L + (i % 9));

            Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId).orElseThrow();
            story.setStartedAt(atWorkingHour(startedOn, 10));
            story.setCompletedAt(atWorkingHour(completedOn, 16));
            storyRepository.save(story);

            // created_at is mapped updatable = false — the ORM asserting that a creation
            // date is a fact, which it is everywhere except in a fixture inventing a
            // past. JDBC is the only way under it, and it is confined to this column.
            jdbc.update("UPDATE stories SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(atWorkingHour(createdOn, 9)), storyId);
        }
    }

    private void returnToBacklog(List<Sized> all, List<Sized> delivered) {
        List<UUID> deliveredIds = delivered.stream().map(Sized::id).toList();
        for (Sized candidate : all) {
            if (deliveredIds.contains(candidate.id())) continue;
            Story story = storyRepository.findByIdAndDeletedAtIsNull(candidate.id()).orElseThrow();
            story.setSprintId(null);
            storyRepository.save(story);
        }
    }

    // ------------------------------------------------------------------- sprints

    /**
     * Closes the sprint at its own dates.
     *
     * <p>Not through {@code SprintService.start} and {@code complete}: {@code start}
     * refuses when another sprint in the project is already active, which every project
     * this runs against has, and both stamp the transition as now. Building history
     * cannot go through a workflow whose whole purpose is to describe the present.</p>
     */
    private void closeSprint(UUID sprintId, LocalDate start, LocalDate end,
                             BigDecimal committed, BigDecimal completed, BigDecimal pulledIn) {
        Sprint sprint = sprintRepository.findByIdAndDeletedAtIsNull(sprintId).orElseThrow();
        sprint.setStatus(SprintStatus.COMPLETED);
        sprint.setCommittedPoints(committed);
        sprint.setCompletedPoints(completed);
        sprint.setStartedAt(atWorkingHour(start, 9));
        sprint.setCompletedAt(atWorkingHour(end, 17));
        // addItems booked every story as scope added, because it books any change to a
        // sprint it did not create. That is right for a running sprint and wrong for the
        // initial commitment, and left alone it reports every seeded sprint as 100%
        // scope volatility — which the M2 requirement-change model reads. Only the work
        // genuinely pulled in mid-sprint counts.
        sprint.setAddedPoints(pulledIn);
        sprint.setRemovedPoints(BigDecimal.ZERO);
        sprintRepository.save(sprint);
    }

    /**
     * One row per day of the sprint, so the burndown is a line rather than a dot.
     *
     * <p>This is the reason the factory exists. {@code SprintMetricsService.snapshot}
     * computes from the backlog as it stands and stamps today, and it is only called on
     * start, on completion, and while a sprint is active — so a sprint created and
     * closed in the same second gets exactly one row, dated today, outside its own date
     * range. Calling it in a loop would not help: it would write today's numbers onto
     * every date and draw a flat line.</p>
     *
     * <p>The curve is deliberately not the ideal line. Nothing burns on the first day,
     * weekends are flat, and the last day takes a disproportionate bite — which is what
     * makes the ideal line worth drawing next to it.</p>
     */
    private void writeDailyMetrics(UUID sprintId, LocalDate start, LocalDate end,
                                   BigDecimal committed, BigDecimal completed) {
        long span = end.toEpochDay() - start.toEpochDay();
        if (span <= 0) return;

        UUID organizationId = TenantContext.requireOrganizationId();
        List<SprintMetric> rows = new ArrayList<>();

        double workingDaysTotal = workingDaysBetween(start, end);
        double workingDaysSoFar = 0;

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!weekend && !date.equals(start)) {
                workingDaysSoFar++;
            }

            double progress = workingDaysTotal == 0 ? 1 : workingDaysSoFar / workingDaysTotal;
            // Slightly behind the ideal for most of the sprint and catching up at the
            // end — the shape almost every real burndown has, and the one that makes a
            // reader look at the gap rather than at the line.
            double eased = Math.pow(progress, 1.35);

            BigDecimal doneByNow = completed.multiply(BigDecimal.valueOf(eased))
                    .setScale(1, RoundingMode.HALF_UP);
            BigDecimal remaining = committed.subtract(doneByNow).max(BigDecimal.ZERO);

            SprintMetric metric = SprintMetric.builder()
                    .sprintId(sprintId)
                    .snapshotDate(date)
                    .committedPoints(committed)
                    .completedPoints(doneByNow)
                    .remainingPoints(remaining)
                    .idealRemaining(idealRemaining(start, end, date, committed))
                    .velocity(doneByNow)
                    .build();
            metric.setOrganizationId(organizationId);
            rows.add(metric);
        }
        metricRepository.saveAll(rows);
    }

    /** The straight line from the full commitment on day one to zero on the last day. */
    private BigDecimal idealRemaining(LocalDate start, LocalDate end, LocalDate on,
                                      BigDecimal committed) {
        double span = end.toEpochDay() - start.toEpochDay();
        double elapsed = on.toEpochDay() - start.toEpochDay();
        return committed.multiply(BigDecimal.valueOf(1 - elapsed / span))
                .max(BigDecimal.ZERO)
                .setScale(1, RoundingMode.HALF_UP);
    }

    private double workingDaysBetween(LocalDate start, LocalDate end) {
        double days = 0;
        for (LocalDate date = start.plusDays(1); !date.isAfter(end); date = date.plusDays(1)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    /**
     * A timestamp inside the working day.
     *
     * <p>Midnight would put every event on the boundary between two dates, and the
     * reports group by date in the server's zone — so a completion stamped 00:00 lands
     * on the previous day for anybody west of UTC.</p>
     */
    private Instant atWorkingHour(LocalDate date, int hour) {
        return date.atTime(LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC);
    }
}
