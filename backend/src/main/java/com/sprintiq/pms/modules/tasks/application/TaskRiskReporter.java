package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.modules.ai.application.SprintRiskAdjustmentService;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    /** Blocking pressure for a full-day task; scaled from there. */
    private static final BigDecimal BLOCKED_BASE = BigDecimal.valueOf(8);
    private static final BigDecimal BLOCKED_CAP = BigDecimal.valueOf(10);

    /** Credit for finishing inside the estimate — the one adjustment that is earned. */
    private static final BigDecimal ON_TIME_CREDIT = BigDecimal.valueOf(-3);

    /** An overdue task cannot cost the sprint more than this, however large its estimate. */
    private static final BigDecimal OVERDUE_CAP = BigDecimal.valueOf(15);

    private static final BigDecimal MINUTES_PER_DAY = BigDecimal.valueOf(8 * 60);

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
        BigDecimal blocked = cap(weight(task).multiply(BLOCKED_BASE), BLOCKED_CAP);

        if (current == TaskStatus.BLOCKED) {
            adjustments.record(task.getSprintId(), RiskType.OVER_BUDGET, blocked,
                    "Task blocked: " + task.getItemKey());
        } else if (previous == TaskStatus.BLOCKED) {
            adjustments.record(task.getSprintId(), RiskType.OVER_BUDGET, blocked.negate(),
                    "Task unblocked: " + task.getItemKey());
        }

        if (current.isCompleted() && !previous.isCompleted()) {
            // Finishing inside the estimate is the evidence that the plan was sound, and
            // a ledger that only ever accumulates bad news would drift upward all sprint
            // for a team that was doing fine.
            if (!task.isOverEstimate() && task.getEstimateMinutes() != null) {
                adjustments.record(task.getSprintId(), RiskType.OVER_BUDGET, ON_TIME_CREDIT,
                        "Completed within estimate: " + task.getItemKey());
            }
            // The task made it out; whatever it was going to cost for running late, it
            // no longer does. Leaving the penalty behind would mean a sprint that
            // recovered still read as one that had not.
            adjustments.clear(task.getSprintId(), overdueKey(task));
        }

        if (task.getReopenedCount() > 0 && previous.isTerminal() && !current.isTerminal()) {
            reopened(task);
        }
    }

    /**
     * Revises this task's rework contribution after a reopen.
     *
     * <p>Revised rather than appended, because a task reopened six times is one task
     * with a worse reading. Six rows would claim six separate breakdowns and would grow
     * without bound; one row that goes up keeps the ledger a description of the sprint
     * rather than a count of clicks.</p>
     */
    public void reopened(Task task) {
        int reopens = task.getReopenedCount();
        if (reopens <= 0) {
            return;
        }
        BigDecimal raw = REOPEN_BASE.add(REOPEN_REPEAT.multiply(BigDecimal.valueOf(reopens - 1L)));
        BigDecimal points = cap(weight(task).multiply(raw), REOPEN_CAP);

        adjustments.revise(task.getSprintId(), RiskType.COMMUNICATION_COLLABORATION,
                "TASK_REOPENED:" + task.getId(), points,
                "Reopened %d time%s: %s".formatted(reopens, reopens == 1 ? "" : "s", task.getItemKey()));
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
        adjustments.clear(task.getSprintId(), "TASK_REOPENED:" + task.getId());
    }

    // --------------------------------------------------------------- internals

    private static String overdueKey(Task task) {
        return "TASK_OVERDUE:" + task.getId();
    }

    /**
     * Task size against a one-day baseline, clamped to 0.5–2.0.
     *
     * <p>Clamped rather than raw so a single fortnight-long task cannot swamp the
     * sprint, and a fifteen-minute one is not treated as free. An unestimated task
     * weighs 1.0 — the absence of an estimate is not evidence that the task is small.</p>
     */
    private static BigDecimal weight(Task task) {
        Integer minutes = task.getEstimateMinutes();
        if (minutes == null || minutes <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal raw = BigDecimal.valueOf(minutes).divide(MINUTES_PER_DAY, 4, RoundingMode.HALF_UP);
        return raw.max(BigDecimal.valueOf(0.5)).min(BigDecimal.valueOf(2.0));
    }

    private static BigDecimal cap(BigDecimal value, BigDecimal limit) {
        return value.min(limit).setScale(2, RoundingMode.HALF_UP);
    }
}
