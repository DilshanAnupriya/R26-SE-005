package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.application.port.CollaborationFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.CollaborationFeatureSource.WatchedTask;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.security.AppProperties;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.CostForecastResponse;
import com.sprintiq.pms.modules.ai.domain.CostOverrun;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Watches active sprints between assessments and moves the risk score as the board moves.
 *
 * <p>The models are only consulted when somebody presses <em>Assess</em>. The events that
 * matter most — a task nobody has spoken about, a question left hanging, work that never
 * started — all happen in between, and a risk figure that only changes on demand teaches
 * a team that it is decorative.</p>
 *
 * <p><b>Every penalty here is reversible.</b> Each writes under a {@code sourceKey} naming
 * the task or comment it came from, and each is withdrawn the moment its condition stops
 * holding. That is what keeps this a measurement rather than a ratchet: a team that
 * answers the question gets the points back, so the number can fall as well as rise. A
 * one-way score would be gamed into irrelevance within a sprint.</p>
 *
 * <p>Tenancy: the sweep reads across every organization because the clock does not stop
 * for tenants nobody logged into, but each sprint's work is done inside
 * {@link TenantContext#runAs} so every row it writes is attributed to exactly one
 * organization — the same rule a request would follow.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SprintWatchService {

    /** A task nobody has said a word about, past the point it should have been half done. */
    static final String KEY_TASK_SILENT = "TASK_SILENT:";
    /** A question left hanging — last comment old, nobody replied. */
    static final String KEY_COMMENT_UNANSWERED = "COMMENT_UNANSWERED:";
    /** Still not started when half its estimate has burned. */
    static final String KEY_NOT_STARTED_HALF = "TASK_NOT_STARTED_HALF:";
    /** Past its estimate and still not finished. */
    static final String KEY_NOT_DONE_OVERDUE = "TASK_NOT_DONE_OVERDUE:";
    /** Tasks under a story are estimated at more time than its points allow. */
    static final String KEY_STORY_OVER_BUDGET = "STORY_OVER_BUDGET:";
    /** The sprint's cost forecast is further over budget than the model's own error. */
    static final String KEY_COST_OVER_BUDGET = "COST_OVER_BUDGET:";

    // Deliberately small. These are nudges accumulated across many tasks, not verdicts:
    // one quiet task should tint a sprint, and a dozen should colour it.
    private static final BigDecimal DELTA_COMMENT_UNANSWERED = BigDecimal.valueOf(4);
    private static final BigDecimal DELTA_OVERDUE_BUDGET = BigDecimal.valueOf(5);

    /*
     * The two half-time rules are weighted by the task, through the same
     * {@link TaskRiskWeight} the board's blocked charge uses: a day's work nobody has
     * started is not the same news as half an hour's. Each base is what a one-day task
     * pays; anything up to half a day weighs 0.5, which keeps a small task at the flat
     * charge these rules used to make (3, 3 and 2).
     */
    private static final BigDecimal SILENT_BASE = BigDecimal.valueOf(6);
    private static final BigDecimal SILENT_CAP = BigDecimal.valueOf(10);

    /**
     * What a silent task still costs once it is finished, as a share of its charge.
     *
     * <p>Finishing earns back half: the work got done. The other half stays, because it
     * was done with nobody discussing it, and that is the collaboration gap the charge
     * recorded. A comment on the task still clears it entirely.</p>
     */
    private static final BigDecimal SILENT_DONE_SHARE = new BigDecimal("0.5");
    private static final BigDecimal NOT_STARTED_BUDGET_BASE = BigDecimal.valueOf(6);
    private static final BigDecimal NOT_STARTED_BUDGET_CAP = BigDecimal.valueOf(10);
    private static final BigDecimal NOT_STARTED_COLLAB_BASE = BigDecimal.valueOf(4);
    private static final BigDecimal NOT_STARTED_COLLAB_CAP = BigDecimal.valueOf(6);

    /**
     * A story's overrun, in risk points: one per half hour past its budget.
     *
     * <p>Proportional rather than flat because the size of the miss is the information.
     * A story ten minutes over is a rounding error in someone's estimate; one a day over
     * means the story was never a 5-pointer, and the sprint plan is built on a number
     * that was wrong. Capped so a single mis-sized story cannot swamp everything else the
     * ledger has recorded.</p>
     */
    private static final BigDecimal MINUTES_PER_OVERRUN_POINT = BigDecimal.valueOf(30);
    private static final BigDecimal MIN_STORY_OVERRUN_DELTA = BigDecimal.valueOf(2);
    private static final BigDecimal MAX_STORY_OVERRUN_DELTA = BigDecimal.valueOf(12);

    /** How long a comment may sit unanswered before it counts as unanswered. */
    private static final Duration UNANSWERED_AFTER = Duration.ofHours(24);

    private final SprintFeatureSource sprints;
    private final CollaborationFeatureSource collaboration;
    private final SprintRiskAdjustmentService adjustments;
    private final StoryFeatureSource stories;
    private final CostForecastService costs;
    private final AppProperties properties;

    /**
     * Every minute. The half-time rules have to land close to the half-time mark itself:
     * a thirty-minute task is due its charge fifteen minutes after it was added, and the
     * fifteen-minute sweep this used to be could notice that as late as the deadline. Each
     * rule is idempotent and an unchanged reading writes nothing, so running more often
     * changes only how quickly a penalty appears or clears.
     */
    @Scheduled(fixedDelayString = "${app.ai.watch-interval-ms:60000}",
               initialDelayString = "${app.ai.watch-initial-delay-ms:60000}")
    public void sweep() {
        List<SprintFeatureSource.ActiveSprint> active;
        try {
            active = sprints.activeSprints();
        } catch (RuntimeException ex) {
            log.warn("Sprint watch could not list active sprints", ex);
            return;
        }
        if (active.isEmpty()) {
            return;
        }

        int watched = 0;
        for (SprintFeatureSource.ActiveSprint sprint : active) {
            if (sprint.organizationId() == null) {
                continue;
            }
            try {
                // Re-enter the sprint's own tenant. The read above crossed tenants; every
                // write below is scoped to one, exactly as a request would be.
                TenantContext.runAs(sprint.organizationId(), null, () -> watch(sprint.sprintId()));
                watched++;
            } catch (RuntimeException ex) {
                // One tenant's bad data must not stop the sweep for everyone else.
                log.warn("Sprint watch failed for sprint {}", sprint.sprintId(), ex);
            }
        }
        log.debug("Sprint watch swept {} of {} active sprints", watched, active.size());
    }

    @Transactional
    public void watch(java.util.UUID sprintId) {
        // Both budget rules read the plan, not the board, so they run before the
        // no-tasks guard below. A sprint whose stories are pointed and whose cost is
        // forecast can be visibly over budget before a single task has been created —
        // that is exactly the moment the warning is worth something, and skipping it
        // because the board is empty would withhold it until it is too late to act.
        storyBudgets(sprintId);
        costBudget(sprintId);

        List<WatchedTask> tasks = collaboration.watchedTasks(sprintId);
        if (tasks.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        java.util.Set<String> silentCharged = chargedKeys(sprintId, KEY_TASK_SILENT);
        for (WatchedTask task : tasks) {
            silentTask(sprintId, task, now, silentCharged);
            unansweredComment(sprintId, task, now);
            notStartedByHalfTime(sprintId, task, now);
            overdue(sprintId, task, now);
        }
    }

    /**
     * The sprint's cost forecast against its budget.
     *
     * <p>The two models that care about going over budget have never compared notes:
     * {@code M2_OVER_BUDGET} predicts the outcome from how the team is behaving, and the
     * cost model measures the money directly. This is the second one reaching the risk
     * score — an overrun the cost model can actually see should not need a behavioural
     * classifier to infer it.</p>
     *
     * <p>Reads the stored forecast rather than running a new one. A sweep that called a
     * model every fifteen minutes for every active sprint would bill for inference
     * nobody asked for; the forecast changes when someone re-forecasts, and this follows
     * it.</p>
     */
    private void costBudget(java.util.UUID sprintId) {
        String key = KEY_COST_OVER_BUDGET + sprintId;

        CostForecastResponse forecast;
        try {
            forecast = costs.latestWithBudget(sprintId);
        } catch (RuntimeException ex) {
            log.warn("Could not read the cost forecast for sprint {}", sprintId, ex);
            return;
        }

        AppProperties.CostRisk config = properties.getAi().getCostRisk();
        CostOverrun overrun = CostOverrun.evaluate(
                forecast == null ? null : forecast.forecastAmount(),
                forecast == null ? null : forecast.budgetAmount(),
                forecast == null ? null : forecast.varianceVsBudgetPct(),
                config.getMarginPct(), config.getPctPerRiskPoint(),
                config.getMinDelta(), config.getMaxDelta());

        if (!overrun.isCharged()) {
            // Under budget, inside the margin, or nothing to compare against. All three
            // withdraw an earlier charge: a forecast that has come back into line should
            // visibly give the points back.
            adjustments.clear(sprintId, key);
            return;
        }

        adjustments.revise(sprintId, RiskType.OVER_BUDGET, key, overrun.delta(),
                String.format("Forecast %s %,.0f against a %s %,.0f budget — %.0f%% over, "
                                + "%.0f points beyond the cost model's usual error",
                        forecast.currency(), overrun.forecastAmount(),
                        forecast.currency(), overrun.budgetAmount(),
                        overrun.variancePct(), overrun.beyondMarginPct()));
    }

    /**
     * Stories whose task breakdown is estimated at more time than their points allow.
     *
     * <p>The link between the two estimates a team keeps. Points say how big the story
     * was thought to be; the tasks underneath say how big it turned out to be once
     * somebody broke it down. When the second exceeds the first, the sprint is carrying
     * work the plan does not account for — which is over-budget risk in the most literal
     * sense available.</p>
     *
     * <p>Revised rather than recorded once, because the overrun is a state and not an
     * event: adding another task should deepen the same entry, not add a second one. And
     * it clears the moment the story is re-pointed or a task re-estimated, so correcting
     * the plan visibly removes the penalty.</p>
     */
    private void storyBudgets(java.util.UUID sprintId) {
        List<StoryFeatureSource.StoryBudgetBreach> breaches;
        try {
            breaches = stories.budgetBreachesForSprint(sprintId);
        } catch (RuntimeException ex) {
            log.warn("Could not read story budgets for sprint {}", sprintId, ex);
            return;
        }

        java.util.Set<java.util.UUID> breaching = new java.util.HashSet<>();
        for (StoryFeatureSource.StoryBudgetBreach breach : breaches) {
            breaching.add(breach.storyId());
            adjustments.revise(sprintId, RiskType.OVER_BUDGET,
                    KEY_STORY_OVER_BUDGET + breach.storyId(),
                    overrunDelta(breach.overrunMinutes()),
                    breach.itemKey() + " is broken into "
                            + describeMinutes(breach.overrunMinutes())
                            + " more work than its "
                            + describeMinutes(breach.budgetMinutes()) + " estimate allows");
        }

        // Withdraw entries for stories that have come back inside their budget. Only the
        // sprint's own stories are considered, so a story moved to another sprint keeps
        // its entry there rather than being silently forgiven by this one.
        for (StoryFeatureSource.StoryBudgetBreach previous : previousBreaches(sprintId)) {
            if (!breaching.contains(previous.storyId())) {
                adjustments.clear(sprintId, KEY_STORY_OVER_BUDGET + previous.storyId());
            }
        }
    }

    /**
     * Which stories this sprint was previously charged for.
     *
     * <p>Read from the ledger rather than recomputed: the entries are the record of what
     * was charged, and a story deleted or moved out of the sprint would otherwise leave a
     * penalty nothing could ever clear.</p>
     */
    private List<StoryFeatureSource.StoryBudgetBreach> previousBreaches(java.util.UUID sprintId) {
        return adjustments.ledgerFor(sprintId).entries().stream()
                .map(entry -> entry.getSourceKey())
                .filter(key -> key != null && key.startsWith(KEY_STORY_OVER_BUDGET))
                .map(key -> key.substring(KEY_STORY_OVER_BUDGET.length()))
                .map(id -> {
                    try {
                        return new StoryFeatureSource.StoryBudgetBreach(
                                java.util.UUID.fromString(id), null, 0, 0);
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private BigDecimal overrunDelta(long overrunMinutes) {
        BigDecimal points = BigDecimal.valueOf(overrunMinutes)
                .divide(MINUTES_PER_OVERRUN_POINT, 1, java.math.RoundingMode.HALF_UP);
        return points.max(MIN_STORY_OVERRUN_DELTA).min(MAX_STORY_OVERRUN_DELTA);
    }

    /** "90 minutes" reads worse than "1h 30m" in a ledger a person scans. */
    private String describeMinutes(long minutes) {
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours == 0) {
            return rest + "m";
        }
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }

    /**
     * A task half its estimate old that nobody has commented on once.
     *
     * <p>Cleared as soon as anyone says anything — the condition is "nobody has spoken",
     * and one comment ends it. Finishing the task without a word only halves it (see
     * {@link #SILENT_DONE_SHARE}); a task finished before it was ever charged owes
     * nothing.</p>
     */
    private void silentTask(java.util.UUID sprintId, WatchedTask task, Instant now,
                            java.util.Set<String> charged) {
        String key = KEY_TASK_SILENT + task.taskId();
        Instant halfTime = task.halfTimeMark();

        if (task.commentCount() > 0 || halfTime == null) {
            adjustments.clear(sprintId, key);
            return;
        }
        BigDecimal charge = TaskRiskWeight.charge(task.estimateMinutes(), SILENT_BASE, SILENT_CAP);
        if (task.isFinished()) {
            // Only a task the rule had already charged keeps a residual. Its half-time mark
            // keeps moving with the wall clock after it is done, so reading that instead
            // would bill a task finished well inside its estimate.
            if (!charged.contains(key)) {
                adjustments.clear(sprintId, key);
                return;
            }
            adjustments.revise(sprintId, RiskType.COMMUNICATION_COLLABORATION, key,
                    charge.multiply(SILENT_DONE_SHARE).setScale(2, java.math.RoundingMode.HALF_UP),
                    "\"" + task.title() + "\" finished with no discussion on it");
            return;
        }
        if (now.isBefore(halfTime)) {
            return;
        }
        // Revised rather than recorded once, so re-estimating the task re-prices it.
        adjustments.revise(sprintId, RiskType.COMMUNICATION_COLLABORATION, key, charge,
                "No discussion on \"" + task.title() + "\" past half its estimate");
    }

    /** The source keys under this prefix the sprint's ledger currently holds. */
    private java.util.Set<String> chargedKeys(java.util.UUID sprintId, String prefix) {
        return adjustments.ledgerFor(sprintId).entries().stream()
                .map(entry -> entry.getSourceKey())
                .filter(key -> key != null && key.startsWith(prefix))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * The last thing said on a task is a day old and nobody has answered it.
     *
     * <p>Withdrawn the moment a newer comment appears, which is what makes answering
     * somebody visibly worth doing.</p>
     */
    private void unansweredComment(java.util.UUID sprintId, WatchedTask task, Instant now) {
        String key = KEY_COMMENT_UNANSWERED + task.taskId();
        Instant last = task.lastCommentAt();

        if (last == null || task.isFinished()) {
            adjustments.clear(sprintId, key);
            return;
        }
        if (last.isAfter(now.minus(UNANSWERED_AFTER))) {
            // Somebody has spoken recently — nothing is hanging.
            adjustments.clear(sprintId, key);
            return;
        }
        adjustments.recordOnce(sprintId, RiskType.COMMUNICATION_COLLABORATION, key,
                DELTA_COMMENT_UNANSWERED,
                "Last comment on \"" + task.title() + "\" is over a day old with no reply");
    }

    /**
     * Still sitting in TODO when half its estimate has already gone.
     *
     * <p>Charged to both models, because it is two different problems at once: nobody
     * has picked it up (coordination) and the sprint is behind where its plan says it
     * should be (budget).</p>
     */
    private void notStartedByHalfTime(java.util.UUID sprintId, WatchedTask task, Instant now) {
        String key = KEY_NOT_STARTED_HALF + task.taskId();
        // This rule is the only one that writes two entries — the same fact charged to
        // two models — so it is the only one that has to withdraw two. Clearing just the
        // collaboration key left the over-budget half standing forever, which made this
        // rule a one-way ratchet: starting the task visibly removed half the penalty and
        // silently kept the rest.
        String budgetKey = key + ":budget";
        Instant halfTime = task.halfTimeMark();

        if (task.hasStarted() || halfTime == null) {
            adjustments.clear(sprintId, key);
            adjustments.clear(sprintId, budgetKey);
            return;
        }
        if (now.isBefore(halfTime)) {
            return;
        }
        adjustments.revise(sprintId, RiskType.COMMUNICATION_COLLABORATION, key,
                TaskRiskWeight.charge(task.estimateMinutes(),
                        NOT_STARTED_COLLAB_BASE, NOT_STARTED_COLLAB_CAP),
                "\"" + task.title() + "\" not started at half its estimate");
        adjustments.revise(sprintId, RiskType.OVER_BUDGET, budgetKey,
                TaskRiskWeight.charge(task.estimateMinutes(),
                        NOT_STARTED_BUDGET_BASE, NOT_STARTED_BUDGET_CAP),
                "\"" + task.title() + "\" not started at half its estimate");
    }

    /** Past the whole estimate and still not done — a budget problem, not a talking one. */
    private void overdue(java.util.UUID sprintId, WatchedTask task, Instant now) {
        String key = KEY_NOT_DONE_OVERDUE + task.taskId();
        Instant deadline = task.deadline();

        if (task.isFinished() || deadline == null) {
            adjustments.clear(sprintId, key);
            return;
        }
        if (now.isBefore(deadline)) {
            return;
        }
        adjustments.recordOnce(sprintId, RiskType.OVER_BUDGET, key,
                DELTA_OVERDUE_BUDGET,
                "\"" + task.title() + "\" is past its estimate and still open");
    }
}
