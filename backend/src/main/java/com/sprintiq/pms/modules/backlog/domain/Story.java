package com.sprintiq.pms.modules.backlog.domain;

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
 * The backlog item — the unit a team estimates, ranks, commits to a sprint and burns
 * down. Everything the product is about hangs off this row.
 *
 * <p>The {@code ai*} fields keep the model's suggestion alongside the human's decision
 * rather than overwriting one with the other. That is what makes Accept / Override /
 * Compare possible in the UI, and it is what the M1 retraining set is built from: a
 * story where the suggestion was 5 and the team chose 8 is far more informative than
 * either number alone.</p>
 */
@Entity
@Table(name = "stories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Story extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "epic_id")
    private UUID epicId;

    /** Null means the story sits in the backlog rather than in a sprint. */
    @Column(name = "sprint_id")
    private UUID sprintId;

    @Column(name = "requirement_id")
    private UUID requirementId;

    /** The board column. Kept in step with {@link #status} on every move. */
    @Column(name = "workflow_state_id")
    private UUID workflowStateId;

    @Column(name = "item_key", nullable = false, length = 24, updatable = false)
    private String itemKey;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "acceptance_criteria", columnDefinition = "text")
    private String acceptanceCriteria;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private StoryType type = StoryType.STORY;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private StoryStatus status = StoryStatus.BACKLOG;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16)
    private Severity severity;

    @Column(name = "story_points", precision = 5, scale = 2)
    private BigDecimal storyPoints;

    // ---- M1 prediction, retained alongside the human decision -----------------

    @Column(name = "ai_story_points", precision = 5, scale = 2)
    private BigDecimal aiStoryPoints;

    /**
     * The model's number before it was snapped to the Fibonacci scale.
     *
     * <p>Kept so a stored suggestion reads the same after a reload as it did the moment
     * it was made. Null for models that do not snap, and for every suggestion recorded
     * before the column existed.</p>
     */
    @Column(name = "ai_raw_points", precision = 6, scale = 2)
    private BigDecimal aiRawPoints;

    @Column(name = "ai_confidence", precision = 4, scale = 3)
    private BigDecimal aiConfidence;

    @Column(name = "ai_lower_bound", precision = 5, scale = 2)
    private BigDecimal aiLowerBound;

    @Column(name = "ai_upper_bound", precision = 5, scale = 2)
    private BigDecimal aiUpperBound;

    @Column(name = "ai_prediction_id")
    private UUID aiPredictionId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "estimate_source", nullable = false, length = 20)
    private EstimateSource estimateSource = EstimateSource.UNESTIMATED;

    // ---------------------------------------------------------------------------

    @Column(name = "business_value")
    private Short businessValue;

    @Builder.Default
    @Column(name = "`rank`", nullable = false, length = 64)
    private String rank = "n";

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "reporter_id")
    private UUID reporterId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Builder.Default
    @Column(name = "blocked", nullable = false)
    private boolean blocked = false;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    /** Times this story came back after being closed — a defect-quality signal for M3. */
    @Builder.Default
    @Column(name = "reopened_count", nullable = false)
    private short reopenedCount = 0;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "ready_checklist", nullable = false, columnDefinition = "jsonb")
    private List<String> readyChecklist = new ArrayList<>();

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "done_checklist", nullable = false, columnDefinition = "jsonb")
    private List<String> doneChecklist = new ArrayList<>();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Moves the story to a new status, maintaining the timestamps and reopen counter
     * that reporting depends on.
     *
     * <p>Kept on the entity rather than in the service because these four fields are
     * only ever correct together: cycle time is measured from {@code startedAt} to
     * {@code completedAt}, and a status change that updates one without the other
     * silently corrupts every cycle-time chart that reads it afterwards.</p>
     */
    public void transitionTo(StoryStatus target) {
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
                // Closed without ever moving through an in-progress column. Cycle time of
                // zero is a lie; recording the same instant at least keeps it non-negative.
                startedAt = completedAt;
            }
        }
        status = target;
    }

    /** Points counted by velocity: only delivered work, and only types that count. */
    public BigDecimal velocityPoints() {
        if (!status.isCompleted() || storyPoints == null || !type.countsTowardVelocity()) {
            return BigDecimal.ZERO;
        }
        return storyPoints;
    }

    public boolean isEstimated() {
        return storyPoints != null;
    }
}
