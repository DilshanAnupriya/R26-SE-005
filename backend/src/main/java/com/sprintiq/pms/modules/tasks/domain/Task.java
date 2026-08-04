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

    public boolean isSubtask() {
        return parentTaskId != null;
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
