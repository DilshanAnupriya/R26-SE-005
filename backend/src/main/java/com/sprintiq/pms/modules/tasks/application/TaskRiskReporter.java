package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.modules.ai.application.SprintRiskAdjustmentService;
import com.sprintiq.pms.modules.ai.application.TaskRiskWeight;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Turns board events into risk points.
 *
 * <p>Separate from {@link TaskService} because it is the only place in the delivery
 * layer that holds an opinion about what a board event is <em>worth</em>. Inlining the
 * numbers would scatter six magic constants through a service that is otherwise about
 * moving rows, and the next person tuning them would have to find all six.</p>
 *
 * <p>Everything here is weighted by task size. A one-hour task bouncing back from
 * review and a three-day task bouncing back are the same event and not remotely the
 * same problem, and a model fed the unweighted count learns that a team splitting its
 * work finely is a team in trouble.</p>
 */
@Component
@RequiredArgsConstructor
public class TaskRiskReporter {

    private final SprintRiskAdjustmentService adjustments;

    /** Points for the first reopen, before weighting. */
    private static final BigDecimal REOPEN_BASE = BigDecimal.valueOf(4);

    /**
     * Points added by each reopen after the first. Smaller than the first on purpose:
     * the initial bounce is the real signal that something was misunderstood, and the
     * ones after it are the same misunderstanding still being worked out.
     */
    private static final BigDecimal REOPEN_REPEAT = BigDecimal.valueOf(1.5);

    /** No single task's rework may dominate a sprint's collaboration score. */
    private static final BigDecimal REOPEN_CAP = BigDecimal.valueOf(12);

    /**
     * Budget cost of the first reopen, before weighting.
     *
     * <p>Six points for a one-day task, the same scale as the board's other per-task
     * charges — a task finished and then taken back out of Done is work the sprint
     * believed was paid for and now has to pay for again. It used to be 2.5, which a
     * small task halved to barely a point: dragging a card out of Done moved the gauge
     * by less than the on-time credit it withdrew, and read as the reopen doing
     * nothing.</p>
     */
    private static final BigDecimal REOPEN_BUDGET_BASE = BigDecimal.valueOf(6);

    /**
     * Budget cost of each reopen after the first — larger than the first, the opposite
     * of the collaboration weighting and deliberately so. Each further pass is work
     * nobody estimated, spent on a task whose estimate was already accounted for, so the
     * budget consequence grows as the count does even though the diagnostic value of the
     * signal falls.
     */
    private static final BigDecimal REOPEN_BUDGET_REPEAT = BigDecimal.valueOf(3);

    /** Nor may one task's rework dominate the over-budget score. */
    private static final BigDecimal REOPEN_BUDGET_CAP = BigDecimal.valueOf(14);

    /** Blocking pressure for a full-day task; scaled from there. */
    private static final BigDecimal BLOCKED_BASE = BigDecimal.valueOf(8);
    private static final BigDecimal BLOCKED_CAP = BigDecimal.valueOf(10);

    /** Credit for finishing inside the estimate — the one adjustment that is earned. */
    private static final BigDecimal ON_TIME_CREDIT = BigDecimal.valueOf(-6);
    private static final BigDecimal ON_TIME_CREDIT_CAP = BigDecimal.valueOf(-10);

    /**
     * Credit for picking a task up before its estimate has run out, for a one-day task.
     *
     * <p>Smaller than finishing on time, because starting is a promise rather than a
     * delivery — but it is still the board saying the plan is holding, and the half-time
     * rule already charges the opposite case. Both credits are weighted by the task
     * exactly as the charges are: half a day's work finished on time is not the same
     * evidence as a fortnight's.</p>
     */
    private static final BigDecimal STARTED_ON_TIME_CREDIT = BigDecimal.valueOf(-4);
    private static final BigDecimal STARTED_ON_TIME_CREDIT_CAP = BigDecimal.valueOf(-8);

    /** Collaboration credit per comment on a one-day task. */
    private static final BigDecimal COMMENT_CREDIT = BigDecimal.valueOf(-2);
    /** Per reply — more than a comment, because a reply is the thread being answered. */
    private static final BigDecimal REPLY_CREDIT = BigDecimal.valueOf(-3);
    /** How many comments, and separately replies, earn credit on one task. */
    private static final long DISCUSSION_COUNTED = 3;

    /** Collaboration charge for a one-day task in a sprint with nobody assigned. */
    private static final BigDecimal UNASSIGNED_BASE = BigDecimal.valueOf(6);
    private static final BigDecimal UNASSIGNED_CAP = BigDecimal.valueOf(10);

    /** An overdue task cannot cost the sprint more than this, however large its estimate. */
    private static final BigDecimal OVERDUE_CAP = BigDecimal.valueOf(15);

    /**
     * A question nobody has answered, charged once the wait passes half the task's
     * estimate.
     *
     * <p>Half the estimate rather than a fixed number of hours, because the same silence
     * means different things at different scales: two hours of nothing on a one-hour task
     * has already sunk it, and on a three-day task is a normal morning. The estimate is
     * the team's own statement of the pace this piece of work runs at, so it is the right
     * clock to measure the silence against.</p>
     */
    private static final BigDecimal UNANSWERED_BASE = BigDecimal.valueOf(5);

    /**
     * Added per further half-estimate of silence, so a question ignored all week keeps
     * getting louder rather than costing what it did in the first hour.
     */
    private static final BigDecimal UNANSWERED_PER_PERIOD = BigDecimal.valueOf(2.5);

    private static final BigDecimal UNANSWERED_CAP = BigDecimal.valueOf(12);


    // ------------------------------------------------------------------- events

    /**
     * Records the risk consequences of a board move.
     *
     * <p>Called after the transition, so {@code task} already carries the new status and
     * the updated reopen count.</p>
     */
    public void statusChanged(Task task, TaskStatus previous) {
        TaskStatus current = task.getStatus();
        if (current == previous) {
            return;
        }
        if (current == TaskStatus.BLOCKED || previous == TaskStatus.BLOCKED) {
            blockChanged(task);
        }

        // Picked up while its estimate still had time on it. Only a pick-up from To Do
        // counts: a card unblocked or sent back from review has not started anything new,
        // and one dragged out of Done is a reopen, charged below. Keyed per task, so
        // dragging a card back and forth between To Do and In Progress earns it once.
        if (current == TaskStatus.IN_PROGRESS && previous == TaskStatus.TODO
                && withinEstimate(task)) {
            adjustments.revise(task.getSprintId(), RiskType.OVER_BUDGET, startedOnTimeKey(task),
                    credit(task, STARTED_ON_TIME_CREDIT, STARTED_ON_TIME_CREDIT_CAP),
                    "Started within estimate: " + task.getItemKey());
        }

        if (current.isCompleted() && !previous.isCompleted()) {
            // Finishing inside the estimate is the evidence that the plan was sound, and
            // a ledger that only ever accumulates bad news would drift upward all sprint
            // for a team that was doing fine.
            //
            // Revised, not appended: "this task finished inside its estimate" is a state,
            // and one task can only be in it once. Appending banked a fresh credit every
            // time a card touched Done, so a task bouncing Done → review → Done earned
            // -3 per lap and cancelled the rework it was accumulating — the sprint's
            // over-budget score sat still through exactly the pattern it exists to catch.
            if (withinEstimate(task)) {
                adjustments.revise(task.getSprintId(), RiskType.OVER_BUDGET, onTimeKey(task),
                        credit(task, ON_TIME_CREDIT, ON_TIME_CREDIT_CAP),
                        "Completed within estimate: " + task.getItemKey());
            }
            // The task made it out; whatever it was going to cost for running late, it
            // no longer does. Leaving the penalty behind would mean a sprint that
            // recovered still read as one that had not.
            adjustments.clear(task.getSprintId(), overdueKey(task));
        }

        if (previous.isTerminal() && !current.isTerminal()) {
            // It is no longer finished, so it is no longer finished on time. Withdrawing
            // the credit is half of what makes a reopen move the score at all; the charge
            // below is the other half.
            adjustments.clear(task.getSprintId(), onTimeKey(task));
            if (task.getReopenedCount() > 0) {
                reopened(task);
            }
        }

        if (previous.isTerminal() != current.isTerminal() && task.getAssigneeId() == null) {
            // An unowned task finished needs nobody; one reopened needs somebody again.
            ownershipChanged(task);
        }
    }

    /**
     * Charges or withdraws the over-budget cost of this task being blocked.
     *
     * <p>Called from every path that can block or unblock a card — the column drag and
     * the Block / Unblock buttons — because a task is blocked whichever way it got there.
     * Before this only the drag charged, so a card blocked from its detail page cost the
     * sprint nothing.</p>
     *
     * <p>One standing entry per card rather than a charge and a matching refund. The pair
     * came apart: the next Assess folded the charge away (the over-budget models never
     * read an in-sprint block, so the score fell straight back) while the refund written
     * after it still counted, and a sprint that blocked and unblocked one card ended up
     * <em>less</em> risky than one that never had. A key held for as long as the card is
     * blocked, and cleared when it is not, cannot drift either way.</p>
     */
    public void blockChanged(Task task) {
        TaskStatus status = task.getStatus();
        boolean blocked = status != null && !status.isTerminal()
                && (task.isBlocked() || status == TaskStatus.BLOCKED);
        blockChanged(task.getSprintId(), task.getId(), task.getItemKey(),
                task.getEstimateMinutes(), blocked);
    }

    /**
     * The same, for a card that is not a task row — a sprint story with no tasks of its
     * own, which the board shows as a card under the story's id.
     */
    public void blockChanged(UUID sprintId, UUID cardId, String itemKey,
                             Integer estimateMinutes, boolean blocked) {
        String key = blockedKey(cardId);
        if (!blocked) {
            adjustments.clear(sprintId, key);
            return;
        }
        adjustments.reviseStanding(sprintId, RiskType.OVER_BUDGET, key,
                cap(weight(estimateMinutes).multiply(BLOCKED_BASE), BLOCKED_CAP),
                "Task blocked: " + itemKey);
    }

    /**
     * Revises this task's rework contribution after a reopen.
     *
     * <p>Revised rather than appended, because a task reopened six times is one task
     * with a worse reading. Six rows would claim six separate breakdowns and would grow
     * without bound; one row that goes up keeps the ledger a description of the sprint
     * rather than a count of clicks.</p>
     *
     * <p>Charged to two risks, because a reopen is evidence about both and the two
     * curves are shaped differently. Collaboration hears the first bounce loudest — that
     * is where the misunderstanding shows — while over-budget hears the later ones
     * loudest, because by then the estimate is long spent and every further pass is
     * unplanned work on the same card.</p>
     */
    public void reopened(Task task) {
        int reopens = task.getReopenedCount();
        if (reopens <= 0) {
            return;
        }
        BigDecimal repeats = BigDecimal.valueOf(reopens - 1L);
        BigDecimal size = weight(task);

        BigDecimal raw = REOPEN_BASE.add(REOPEN_REPEAT.multiply(repeats));
        adjustments.revise(task.getSprintId(), RiskType.COMMUNICATION_COLLABORATION,
                reopenKey(task), cap(size.multiply(raw), REOPEN_CAP),
                "Reopened %d time%s: %s".formatted(reopens, reopens == 1 ? "" : "s", task.getItemKey()));

        reworkCharged(task.getSprintId(), task.getId(), task.getItemKey(),
                task.getEstimateMinutes(), reopens);
    }

    /**
     * Charges over-budget for a story card taken back out of Done.
     *
     * <p>A sprint story with no tasks of its own is a card on the board like any other,
     * and dragging it out of Done is the same news. It is charged to over-budget only:
     * the collaboration model reads reopens off task rows, and a story card has none.</p>
     */
    public void storyReopened(UUID sprintId, UUID storyId, String itemKey,
                              Integer estimateMinutes, int reopens) {
        if (reopens <= 0) {
            return;
        }
        reworkCharged(sprintId, storyId, itemKey, estimateMinutes, reopens);
    }

    /** The over-budget cost of a card's reopens — larger with each one, scaled by size. */
    private void reworkCharged(UUID sprintId, UUID cardId, String itemKey,
                               Integer estimateMinutes, int reopens) {
        BigDecimal repeats = BigDecimal.valueOf(reopens - 1L);
        BigDecimal budgetRaw = REOPEN_BUDGET_BASE.add(REOPEN_BUDGET_REPEAT.multiply(repeats));
        adjustments.revise(sprintId, RiskType.OVER_BUDGET,
                reopenBudgetKey(cardId),
                cap(weight(estimateMinutes).multiply(budgetRaw), REOPEN_BUDGET_CAP),
                "Rework on %s: %d reopen%s beyond the estimate"
                        .formatted(itemKey, reopens, reopens == 1 ? "" : "s"));
    }

    /**
     * Charges the sprint for a question on this task that nobody has answered.
     *
     * <p>Collaboration rather than over-budget: an unanswered comment is the team failing
     * to talk to itself, which may or may not end up costing effort. The over-budget
     * model hears about it only if the silence turns into a task that misses its
     * estimate, which it measures for itself.</p>
     *
     * <p>Revised, not appended, and keyed on the task: one card with a stale question is
     * one problem however many times the board notices it. Silent when the task has no
     * estimate — there is no half of an unstated duration, and guessing one would charge
     * every unestimated card the moment somebody commented on it.</p>
     */
    public void commentUnanswered(Task task, Instant askedAt) {
        Integer estimateMinutes = task.getEstimateMinutes();
        if (askedAt == null || estimateMinutes == null || estimateMinutes <= 0) {
            return;
        }
        long patienceMinutes = Math.max(1L, estimateMinutes / 2L);
        long waitedMinutes = Duration.between(askedAt, Instant.now()).toMinutes();
        long periods = waitedMinutes / patienceMinutes;
        if (periods < 1) {
            adjustments.clear(task.getSprintId(), unansweredKey(task));
            return;
        }

        BigDecimal raw = UNANSWERED_BASE
                .add(UNANSWERED_PER_PERIOD.multiply(BigDecimal.valueOf(periods - 1L)));
        adjustments.revise(task.getSprintId(), RiskType.COMMUNICATION_COLLABORATION,
                unansweredKey(task), cap(weight(task).multiply(raw), UNANSWERED_CAP),
                "Unanswered on %s for %s".formatted(task.getItemKey(), humanise(waitedMinutes)));
    }

    /**
     * Charges collaboration for a task in a sprint that nobody owns, and settles that
     * charge once somebody does.
     *
     * <p>Unassigned, the task pays its full weighted charge: work nobody has picked up is
     * the most basic coordination failure a board can show. Assigned, it keeps only the
     * share of that charge matching the part of its estimate that ran out while it waited
     * — picked up straight away, nothing is left; picked up with a third of its time gone,
     * a third stays.</p>
     *
     * <p>Extending the estimate afterwards gives that time back: every minute added
     * cancels a minute of the wait, and an extension as long as the wait clears the
     * charge. Called after creation, assignment, re-estimation, a sprint move and a move
     * into or out of Done, so the entry always describes the task as it stands.</p>
     */
    public void ownershipChanged(Task task) {
        if (task.getSprintId() == null) {
            return;
        }
        String key = unassignedKey(task);
        TaskStatus status = task.getStatus();
        boolean finished = status != null && status.isTerminal();

        if (task.getAssigneeId() == null) {
            if (finished) {
                // Done without an owner: there is no work left for anybody to pick up.
                adjustments.clear(task.getSprintId(), key);
                return;
            }
            adjustments.revise(task.getSprintId(), RiskType.COMMUNICATION_COLLABORATION, key,
                    cap(weight(task).multiply(UNASSIGNED_BASE), UNASSIGNED_CAP),
                    "Nobody assigned to " + task.getItemKey());
            return;
        }

        Long waitedSeconds = task.getUnassignedElapsedSeconds();
        Integer estimateThen = task.getEstimateAtAssignMinutes();
        if (waitedSeconds == null || estimateThen == null || estimateThen <= 0) {
            // Owned from the start, or never estimated — no share of an estimate was lost.
            adjustments.clear(task.getSprintId(), key);
            return;
        }
        long estimateNow = task.getEstimateMinutes() == null ? estimateThen : task.getEstimateMinutes();
        long extensionSeconds = Math.max(0L, estimateNow - estimateThen) * 60L;
        long lostSeconds = Math.max(0L, waitedSeconds - extensionSeconds);
        // Under a minute is assigning it straight away — the time it takes to pick a name
        // — and left a "+0.02, waited 0m" row on the ledger that described nothing.
        if (lostSeconds < 60) {
            adjustments.clear(task.getSprintId(), key);
            return;
        }
        BigDecimal lostShare = BigDecimal.valueOf(Math.min(lostSeconds, estimateThen * 60L))
                .divide(BigDecimal.valueOf(estimateThen * 60L), 4, RoundingMode.HALF_UP);
        BigDecimal fullCharge = cap(weight(estimateThen).multiply(UNASSIGNED_BASE), UNASSIGNED_CAP);
        BigDecimal residual = fullCharge.multiply(lostShare).setScale(2, RoundingMode.HALF_UP);
        if (residual.signum() == 0) {
            adjustments.clear(task.getSprintId(), key);
            return;
        }
        adjustments.revise(task.getSprintId(), RiskType.COMMUNICATION_COLLABORATION, key, residual,
                "%s waited %s for an owner — %d%% of its estimate"
                        .formatted(task.getItemKey(), humanise(lostSeconds / 60),
                                lostShare.multiply(BigDecimal.valueOf(100)).intValue()));
    }

    /** A task left this sprint: its ownership charge goes with it. */
    public void leftSprint(Task task, UUID previousSprintId) {
        if (previousSprintId != null && !previousSprintId.equals(task.getSprintId())) {
            adjustments.clear(previousSprintId, unassignedKey(task));
        }
    }

    /**
     * Credits collaboration for the discussion on this task: its comments, and the
     * replies to them.
     *
     * <p>Talking on the task is the behaviour the collaboration score exists to reward,
     * and a ledger that could only charge silence drifted up for a team that was talking
     * perfectly well. A reply is worth more than a comment, because it is the thread
     * being answered rather than only opened.</p>
     *
     * <p>Recomputed from the thread's counts on every change rather than appended per
     * comment, for the same reason as the reopen charge: deleting a comment gives its
     * credit back, and the entry describes the thread rather than the clicks. Only the
     * first {@value #DISCUSSION_COUNTED} of each count, or a task could be talked into a
     * negative risk with a dozen "+1"s. Weighted like every other per-task entry.</p>
     */
    public void discussionChanged(Task task, long comments, long replies) {
        discussionCredit(task, commentedKey(task), comments, COMMENT_CREDIT, "comment", "comments");
        discussionCredit(task, repliedKey(task), replies, REPLY_CREDIT, "reply", "replies");
        if (comments + replies > 0) {
            // The sweep's "nobody has discussed this" charge, withdrawn now rather than at
            // its next pass, so the comment visibly ends the silence it was charged for.
            adjustments.clear(task.getSprintId(), "TASK_SILENT:" + task.getId());
        }
    }

    private void discussionCredit(Task task, String key, long count, BigDecimal perItem,
                                  String one, String many) {
        if (count <= 0) {
            adjustments.clear(task.getSprintId(), key);
            return;
        }
        long counted = Math.min(count, DISCUSSION_COUNTED);
        BigDecimal credit = weight(task).multiply(perItem).multiply(BigDecimal.valueOf(counted))
                .setScale(2, RoundingMode.HALF_UP);
        adjustments.revise(task.getSprintId(), RiskType.COMMUNICATION_COLLABORATION, key, credit,
                "%d %s on %s".formatted(count, count == 1 ? one : many, task.getItemKey()));
    }

    /** The question was answered, or the comment went away. Withdraw the charge. */
    public void commentAnswered(Task task) {
        adjustments.clear(task.getSprintId(), unansweredKey(task));
    }

    /**
     * Charges the sprint for a task that has run past its estimate and is still open.
     *
     * <p>Deliberately distinct from the overdue badge, which is about how a task
     * <em>finished</em>. This is about one still in flight with its estimate gone, which
     * is the situation the over-budget model exists to catch early.</p>
     *
     * <p>Idempotent by source key. Several open browser tabs will all notice the same
     * countdown hit zero within the same second, and without this the sprint's risk
     * would become a function of how many people were watching it.</p>
     */
    public void overdue(Task task) {
        if (task.getStatus().isTerminal() || task.getEstimateMinutes() == null) {
            return;
        }
        BigDecimal hours = BigDecimal.valueOf(task.getEstimateMinutes())
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        adjustments.recordOnce(task.getSprintId(), RiskType.OVER_BUDGET, overdueKey(task),
                cap(hours, OVERDUE_CAP), "Estimate exceeded, still open: " + task.getItemKey());
    }

    /** Withdraws everything a task contributed — it is being deleted. */
    public void removed(Task task) {
        adjustments.clear(task.getSprintId(), overdueKey(task));
        adjustments.clear(task.getSprintId(), reopenKey(task));
        adjustments.clear(task.getSprintId(), reopenBudgetKey(task.getId()));
        adjustments.clear(task.getSprintId(), onTimeKey(task));
        adjustments.clear(task.getSprintId(), startedOnTimeKey(task));
        adjustments.clear(task.getSprintId(), commentedKey(task));
        adjustments.clear(task.getSprintId(), repliedKey(task));
        adjustments.clear(task.getSprintId(), unassignedKey(task));
        adjustments.clear(task.getSprintId(), unansweredKey(task));
        adjustments.clear(task.getSprintId(), blockedKey(task.getId()));
    }

    // --------------------------------------------------------------- internals

    private static String overdueKey(Task task) {
        return "TASK_OVERDUE:" + task.getId();
    }

    private static String reopenKey(Task task) {
        return "TASK_REOPENED:" + task.getId();
    }

    private static String reopenBudgetKey(UUID cardId) {
        return "TASK_REOPEN_BUDGET:" + cardId;
    }

    /**
     * Whether the task is still inside its estimate on the board's own clock.
     *
     * <p>The clock the card counts down with: time in flight since it was added, paused
     * while it sits in Done. This used to read logged effort instead, which almost no
     * team keeps — so every finished task was "on time", however late the card had run.
     * Logged effort past the estimate still disqualifies it; either one is enough to say
     * the estimate did not hold.</p>
     */
    private static boolean withinEstimate(Task task) {
        return task.getEstimateMinutes() != null && task.getEstimateMinutes() > 0
                && !task.isOverdueAgainstEstimate(Instant.now())
                && !task.isOverEstimate();
    }

    /** A credit, scaled by the task's size and held above {@code floor}. */
    private static BigDecimal credit(Task task, BigDecimal base, BigDecimal floor) {
        return weight(task).multiply(base).max(floor).setScale(2, RoundingMode.HALF_UP);
    }

    private static String unassignedKey(Task task) {
        return "TASK_UNASSIGNED:" + task.getId();
    }

    private static String commentedKey(Task task) {
        return "TASK_COMMENTED:" + task.getId();
    }

    private static String repliedKey(Task task) {
        return "TASK_REPLIED:" + task.getId();
    }

    private static String startedOnTimeKey(Task task) {
        return "TASK_STARTED_ON_TIME:" + task.getId();
    }

    private static String onTimeKey(Task task) {
        return "TASK_ON_TIME:" + task.getId();
    }

    private static String unansweredKey(Task task) {
        return "TASK_UNANSWERED:" + task.getId();
    }

    private static String blockedKey(UUID cardId) {
        return "TASK_BLOCKED:" + cardId;
    }

    /** "3h" and "2d" read on a ledger row; "185 minutes" does not. */
    private static String humanise(long minutes) {
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        return hours < 24 ? hours + "h" : (hours / 24) + "d";
    }

    /**
     * Task size against a one-day baseline, clamped to 0.5–2.0.
     *
     * <p>Clamped rather than raw so a single fortnight-long task cannot swamp the
     * sprint, and a fifteen-minute one is not treated as free. An unestimated task
     * weighs 1.0 — the absence of an estimate is not evidence that the task is small.</p>
     */
    private static BigDecimal weight(Task task) {
        return weight(task.getEstimateMinutes());
    }

    private static BigDecimal weight(Integer minutes) {
        // Shared with the sprint watch, so a card is the same size to every rule.
        return TaskRiskWeight.of(minutes);
    }

    private static BigDecimal cap(BigDecimal value, BigDecimal limit) {
        return value.min(limit).setScale(2, RoundingMode.HALF_UP);
    }
}
