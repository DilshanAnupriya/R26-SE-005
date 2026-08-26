package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.application.port.CollaborationFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.CollaborationFeatureSource.WatchedTask;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.domain.RiskType;
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

    // Deliberately small. These are nudges accumulated across many tasks, not verdicts:
    // one quiet task should tint a sprint, and a dozen should colour it.
    private static final BigDecimal DELTA_TASK_SILENT = BigDecimal.valueOf(3);
    private static final BigDecimal DELTA_COMMENT_UNANSWERED = BigDecimal.valueOf(4);
    private static final BigDecimal DELTA_NOT_STARTED_COLLAB = BigDecimal.valueOf(2);
    private static final BigDecimal DELTA_NOT_STARTED_BUDGET = BigDecimal.valueOf(3);
    private static final BigDecimal DELTA_OVERDUE_BUDGET = BigDecimal.valueOf(5);

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

    /**
     * Every fifteen minutes: frequent enough that the number tracks the board, rare
     * enough that it is not a load generator. Each rule is idempotent, so running more
     * often changes nothing except how quickly a penalty appears or clears.
     */
    @Scheduled(fixedDelayString = "${app.ai.watch-interval-ms:900000}",
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
        List<WatchedTask> tasks = collaboration.watchedTasks(sprintId);
        if (tasks.isEmpty()) {
            return;
        }
        storyBudgets(sprintId);

        Instant now = Instant.now();
        for (WatchedTask task : tasks) {
            silentTask(sprintId, task, now);
            unansweredComment(sprintId, task, now);
            notStartedByHalfTime(sprintId, task, now);
            overdue(sprintId, task, now);
        }
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
     * and one comment ends it.</p>
     */
    private void silentTask(java.util.UUID sprintId, WatchedTask task, Instant now) {
        String key = KEY_TASK_SILENT + task.taskId();
        Instant halfTime = task.halfTimeMark();

        if (task.commentCount() > 0 || task.isFinished() || halfTime == null) {
            adjustments.clear(sprintId, key);
            return;
        }
        if (now.isBefore(halfTime)) {
            return;
        }
        adjustments.recordOnce(sprintId, RiskType.COMMUNICATION_COLLABORATION, key,
                DELTA_TASK_SILENT,
                "No discussion on \"" + task.title() + "\" past half its estimate");
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
        adjustments.recordOnce(sprintId, RiskType.COMMUNICATION_COLLABORATION, key,
                DELTA_NOT_STARTED_COLLAB,
                "\"" + task.title() + "\" not started at half its estimate");
        adjustments.recordOnce(sprintId, RiskType.OVER_BUDGET, budgetKey,
                DELTA_NOT_STARTED_BUDGET,
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
