package com.sprintiq.pms.modules.tasks.domain;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.domain.Severity;
import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A unit of execution, normally under a story. Subtasks are tasks with a
 * {@code parentTaskId} — one table, because a subtask needs every field a task has
 * (assignee, estimate, time entries) and a separate table would duplicate all of them.
 *
 * <p>{@code complexityScore} and {@code requiredSkills} exist for the M3 allocation
 * model: ranking developers for a task needs to know how hard it is and what it calls
 * for, and neither is recoverable from the title.</p>
 */
@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "story_id")
    private UUID storyId;

    /** Set on subtasks. Null for top-level tasks. */
    @Column(name = "parent_task_id")
    private UUID parentTaskId;

    @Column(name = "sprint_id")
    private UUID sprintId;

    @Column(name = "workflow_state_id")
    private UUID workflowStateId;

    @Column(name = "item_key", nullable = false, length = 24, updatable = false)
    private String itemKey;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private TaskType type = TaskType.TASK;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private TaskStatus status = TaskStatus.TODO;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16)
    private Severity severity;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "reporter_id")
    private UUID reporterId;

    @Column(name = "estimate_minutes")
    private Integer estimateMinutes;

    /** Sum of this task's time entries, maintained on every log. */
    @Builder.Default
    @Column(name = "spent_minutes", nullable = false)
    private int spentMinutes = 0;

    @Column(name = "remaining_minutes")
    private Integer remainingMinutes;

    /** M3 feature: how demanding this task is, 0–10. */
    @Column(name = "complexity_score", precision = 4, scale = 2)
    private BigDecimal complexityScore;

    /** M3 feature: skills the task calls for, matched against developer profiles. */
    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "required_skills", nullable = false, columnDefinition = "jsonb")
    private List<String> requiredSkills = new ArrayList<>();

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Builder.Default
    @Column(name = "`rank`", nullable = false, length = 64)
    private String rank = "n";

    @Builder.Default
    @Column(name = "blocked", nullable = false)
    private boolean blocked = false;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Builder.Default
    @Column(name = "reopened_count", nullable = false)
    private short reopenedCount = 0;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_source", nullable = false, length = 20)
    private AssignmentSource assignmentSource = AssignmentSource.MANUAL;

    @Column(name = "ai_prediction_id")
    private UUID aiPredictionId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Working seconds banked from runs that have already ended.
     *
     * <p>Distinct from {@code spentMinutes}, which is effort somebody chose to log.
     * This is wall-clock time the card has spent outside a terminal column, and the
     * two answer different questions: a task can be open for three days with forty
     * minutes logged against it, and that gap is the interesting part.</p>
     */
    @Builder.Default
    @Column(name = "elapsed_seconds", nullable = false)
    private long elapsedSeconds = 0L;

    /**
     * Start of the current run, or null when the clock is paused — which is exactly
     * when the task is terminal. Live elapsed time is
     * {@code elapsedSeconds + (runningSince == null ? 0 : now - runningSince)}.
     */
    @Column(name = "running_since")
    private Instant runningSince;

    public boolean isSubtask() {
        return parentTaskId != null;
    }

    /**
     * Counts a reopen that did not move the card.
     *
     * <p>Opening a task for another look is rework in the same sense a bounce back from
     * review is: the work was taken to be settled and somebody is handling it again.
     * {@link #transitionTo} counts the reopens that move a card between columns; this
     * counts the ones that do not, and the two never see the same event, so nothing is
     * counted twice.</p>
     *
     * <p>Saturates at {@link Short#MAX_VALUE} rather than wrapping. The counter is a
     * {@code short} on the row, and a card opened thirty-three thousand times reading as
     * a negative number would take the sprint's risk with it.</p>
     *
     * @return the count after this reopen
     */
    public short countReopen() {
        if (reopenedCount < Short.MAX_VALUE) {
            reopenedCount += 1;
        }
        return reopenedCount;
    }

    /**
     * Moves the task, keeping the timestamps, reopen counter and blocked flag coherent.
     * Same reasoning as {@code Story.transitionTo}: these fields are only ever correct
     * together, and cycle-time reporting reads all of them.
     */
    public void transitionTo(TaskStatus target) {
        if (target == status) {
            return;
        }
        applyWorkClock(status, target);
        if (target.isReopenFrom(status)) {
            reopenedCount += 1;
            completedAt = null;
        }
        if (target.isStarted() && startedAt == null) {
            startedAt = Instant.now();
        }
        if (target.isCompleted()) {
            completedAt = Instant.now();
            if (startedAt == null) {
                startedAt = completedAt;
            }
            // Finished work cannot still be blocked.
            blocked = false;
            blockedReason = null;
            blockedAt = null;
            remainingMinutes = 0;
        }
        // BLOCKED as a status and the blocked flag are two views of one fact; a caller
        // that sets only one would leave the impediment board disagreeing with the board.
        if (target == TaskStatus.BLOCKED) {
            blocked = true;
            if (blockedAt == null) {
                blockedAt = Instant.now();
            }
        } else if (status == TaskStatus.BLOCKED) {
            blocked = false;
            blockedReason = null;
            blockedAt = null;
        }
        status = target;
    }

    /**
     * Pauses or resumes the work clock as the task crosses the terminal boundary.
     *
     * <ul>
     *   <li><b>into DONE or CANCELLED</b> — bank the current run and clear
     *       {@code runningSince}. The total is frozen from this moment, so the time on
     *       the card is the time the task actually took rather than the time since it
     *       was created.</li>
     *   <li><b>back out</b> — start a new run from now, leaving the banked total alone,
     *       so a reopened task picks up where it froze instead of restarting or being
     *       charged for the fortnight it sat in Done.</li>
     *   <li><b>between two open columns</b> — nothing; the clock keeps running.</li>
     * </ul>
     *
     * <p>Private, and driven from {@link #transitionTo} rather than exposed: a caller
     * that set the status without moving the clock would leave a card counting up in
     * Done, and there is no legitimate reason to want that.</p>
     */
    private void applyWorkClock(TaskStatus from, TaskStatus to) {
        boolean wasStopped = from.isTerminal();
        boolean isStopped = to.isTerminal();
        if (wasStopped == isStopped) {
            return;
        }
        if (isStopped) {
            if (runningSince != null) {
                elapsedSeconds += Math.max(0L, Duration.between(runningSince, Instant.now()).getSeconds());
            }
            runningSince = null;
        } else {
            runningSince = Instant.now();
        }
    }

    /**
     * Wall-clock seconds this task has been in flight, as of {@code now}.
     *
     * <p>Recomputed rather than stored, because a running clock has no single correct
     * stored value — the row would be stale the instant after it was written.</p>
     */
    public long elapsedSecondsAt(Instant now) {
        long live = runningSince == null
                ? 0L
                : Math.max(0L, Duration.between(runningSince, now).getSeconds());
        return elapsedSeconds + live;
    }

    /**
     * Whether the task has burned through its estimate in elapsed time.
     *
     * <p>Deliberately not {@link #isOverEstimate()}, which compares logged effort. A
     * task can be well inside its logged budget and days past its estimate in wall
     * time, and the board card is asking the second question.</p>
     */
    public boolean isOverdueAgainstEstimate(Instant now) {
        return estimateMinutes != null && estimateMinutes > 0
                && elapsedSecondsAt(now) > (long) estimateMinutes * 60L;
    }

    /** Logs effort and keeps the remaining estimate in step. */
    public void logMinutes(int minutes) {
        spentMinutes += minutes;
        if (estimateMinutes != null) {
            remainingMinutes = Math.max(0, estimateMinutes - spentMinutes);
        }
    }

    /** Over the original estimate — surfaced on the timesheet and fed to the M4 cost model. */
    public boolean isOverEstimate() {
        return estimateMinutes != null && spentMinutes > estimateMinutes;
    }
}
