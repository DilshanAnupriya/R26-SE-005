package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Signed risk points recorded against a sprint between model runs.
 *
 * <p>A classifier scores a sprint at a moment. The board then keeps moving, and a
 * dashboard whose number only changes when somebody presses <em>Assess</em> teaches
 * everyone that the number is decorative. So evidence that arrives after a scoring —
 * a blocked task, a task pulled back out of Done for the third time, scope cut — lands
 * here as points against the model it bears on, and the read path adds them to the
 * stored score.</p>
 *
 * <p>This is deliberately an adjustment layer rather than a re-prediction. The model's
 * own output stays on the {@link RiskAssessment} row it was written to, unedited and
 * auditable, and every adjusted figure decomposes back into "the model said X, the
 * board has since added Y". Re-scoring on every board move would also mean a
 * retrospective could not reproduce the number anyone acted on.</p>
 *
 * <p>{@code sourceKey} separates the two kinds of evidence. Most adjustments are
 * events and simply accumulate. Some describe a <em>state</em> — a task is overdue
 * once however many browser tabs notice, and a task reopened six times is one task
 * with a worse reading, not six separate signals. Those carry a key, are unique per
 * sprint by database constraint, and are revised in place.</p>
 */
@Entity
@Table(name = "sprint_risk_adjustments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintRiskAdjustment extends TenantAwareEntity {

    @Column(name = "sprint_id", nullable = false, updatable = false)
    private UUID sprintId;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_type", nullable = false, length = 40, updatable = false)
    private RiskType riskType;

    /** Positive worsens the sprint's outlook, negative improves it. */
    @Column(name = "delta", nullable = false, precision = 6, scale = 2)
    private BigDecimal delta;

    /** Shown verbatim in the adjustment ledger, so it is written as a sentence. */
    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    /**
     * Identity of a revisable signal, e.g. {@code TASK_REOPENED:<taskId>}. Null for
     * one-off events, which is what lets them accumulate rather than overwrite.
     */
    @Column(name = "source_key", length = 120, updatable = false)
    private String sourceKey;

    /**
     * Whether a re-scoring can absorb this entry, or has to keep paying it.
     *
     * <p>Most of the ledger is board state the models re-read on every assessment: a
     * blocked task is in {@code blocked_count}, a reopen is in the rework columns. For
     * those, folding the overlay into the model's own figure is exactly right, and
     * {@code false} is the correct default.</p>
     *
     * <p>The requirement-change register is the exception, and not by oversight. A
     * logged change is a historical fact about a sprint that no feature vector carries —
     * the models read requirement churn off the requirements table, and a project that
     * tracks its work as stories has none. Discarding those charges at the next Assess
     * left the register describing churn the score denied.</p>
     */
    @Builder.Default
    @Column(name = "survives_scoring", nullable = false)
    private boolean survivesScoring = false;
}
