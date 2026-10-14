package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustment;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What "Assess" is allowed to throw away.
 *
 * <p>The shown risk is the model's score plus a ledger overlay, and re-scoring clears
 * that overlay on the premise that the model has now read the same evidence for itself.
 * The premise holds for board state — a blocked task is in {@code blocked_count}, a
 * reopen is in the rework columns — and re-scoring those is exactly right.</p>
 *
 * <p>It does not hold for the requirement-change register. No feature vector carries "a
 * scope change was logged on the ninth": the models read requirement churn off the
 * requirements table, and a project that tracks its work as stories has none at all. So
 * the charge landed, Assess wiped it, and the score fell back to where it began while the
 * register went on describing churn the number denied.</p>
 *
 * <p>These pin both halves. Getting the second one wrong looks like the risk score fixing
 * itself; getting the first one wrong charges a sprint twice for one blocked task.</p>
 */
@DisplayName("Adjustments across a re-assessment")
class AdjustmentsSurviveAssessTest {

    private static final Instant SCORED_AT = Instant.parse("2026-08-25T12:00:00Z");
    private static final Instant BEFORE = SCORED_AT.minus(Duration.ofHours(3));
    private static final Instant AFTER = SCORED_AT.plus(Duration.ofHours(1));

    private final List<SprintRiskAdjustment> entries = new ArrayList<>();

    private SprintRiskAdjustmentService.Ledger ledger() {
        return new SprintRiskAdjustmentService.Ledger(List.copyOf(entries));
    }

    private void entry(RiskType type, double delta, Instant at, boolean survives) {
        SprintRiskAdjustment adjustment = SprintRiskAdjustment.builder()
                .sprintId(UUID.randomUUID())
                .riskType(type)
                .delta(BigDecimal.valueOf(delta))
                .reason("test")
                .survivesScoring(survives)
                .build();
        adjustment.setCreatedAt(at);
        entries.add(adjustment);
    }

    @Nested
    @DisplayName("Board readings")
    class BoardReadings {

        @Test
        @DisplayName("are folded into the model's own figure and stop counting")
        void absorbedByScoring() {
            entry(RiskType.OVER_BUDGET, 8, BEFORE, false);

            // The model re-read the blocked task for itself, so the overlay would be
            // charging the sprint a second time for evidence already in the score.
            assertThat(ledger().netSince(RiskType.OVER_BUDGET, SCORED_AT)).isZero();
        }

        @Test
        @DisplayName("still count when they arrived after the scoring")
        void countedWhenNewerThanTheScoring() {
            entry(RiskType.OVER_BUDGET, 8, AFTER, false);

            assertThat(ledger().netSince(RiskType.OVER_BUDGET, SCORED_AT))
                    .isEqualByComparingTo(BigDecimal.valueOf(8));
        }
    }

    @Nested
    @DisplayName("Logged requirement changes")
    class LoggedChanges {

        @Test
        @DisplayName("keep counting after a re-assessment")
        void survive() {
            entry(RiskType.REQUIREMENT_CHANGE, 8, BEFORE, true);

            // The whole fix in one assertion. Without it this is zero, the score drops
            // back to its pre-change value, and the register describes churn the number
            // says never happened.
            assertThat(ledger().netSince(RiskType.REQUIREMENT_CHANGE, SCORED_AT))
                    .isEqualByComparingTo(BigDecimal.valueOf(8));
        }

        @Test
        @DisplayName("accumulate across several assessments")
        void accumulate() {
            entry(RiskType.REQUIREMENT_CHANGE, 8, BEFORE, true);
            entry(RiskType.REQUIREMENT_CHANGE, 5, SCORED_AT.minus(Duration.ofMinutes(5)), true);
            entry(RiskType.REQUIREMENT_CHANGE, 4, AFTER, true);

            assertThat(ledger().netSince(RiskType.REQUIREMENT_CHANGE, SCORED_AT))
                    .isEqualByComparingTo(BigDecimal.valueOf(17));
        }

        @Test
        @DisplayName("a withdrawal cancels the charge it reverses, whenever either landed")
        void withdrawalCancels() {
            entry(RiskType.REQUIREMENT_CHANGE, 8, BEFORE, true);
            // Withdrawn after the sprint was re-assessed. If the reversal expired at the
            // cutoff while the original stood, withdrawing a change would leave the
            // sprint permanently paying for it.
            entry(RiskType.REQUIREMENT_CHANGE, -8, AFTER, true);

            assertThat(ledger().netSince(RiskType.REQUIREMENT_CHANGE, SCORED_AT)).isZero();
        }

        @Test
        @DisplayName("a removal lowers the score as much as an addition raised it")
        void removalsLower() {
            entry(RiskType.REQUIREMENT_CHANGE, -5, BEFORE, true);

            assertThat(ledger().netSince(RiskType.REQUIREMENT_CHANGE, SCORED_AT))
                    .isEqualByComparingTo(BigDecimal.valueOf(-5));
        }
    }

    @Test
    @DisplayName("standing charges are still bounded, and still responsive inside the bound")
    void stillSoftCapped() {
        // Four scope changes is 32 raw points, past the knee. The curve keeps moving
        // there, which is the property that matters: a sprint that has banked its
        // ceiling must still register the next change.
        for (int i = 0; i < 4; i++) {
            entry(RiskType.REQUIREMENT_CHANGE, 8, BEFORE, true);
        }
        double four = ledger().netSince(RiskType.REQUIREMENT_CHANGE, SCORED_AT).doubleValue();

        entry(RiskType.REQUIREMENT_CHANGE, 8, BEFORE, true);
        double five = ledger().netSince(RiskType.REQUIREMENT_CHANGE, SCORED_AT).doubleValue();

        assertThat(five).isGreaterThan(four).isLessThanOrEqualTo(30.0);

        // Twenty changes is a sprint in trouble, not a sprint at 160% risk. Far enough
        // out, the increments fall below the four decimals the cap keeps and the total
        // settles on the limit exactly — asserted as it behaves rather than as the
        // tempting "never reaches it", which is true of the curve and not the arithmetic.
        for (int i = 0; i < 15; i++) {
            entry(RiskType.REQUIREMENT_CHANGE, 8, BEFORE, true);
        }
        assertThat(ledger().netSince(RiskType.REQUIREMENT_CHANGE, SCORED_AT).doubleValue())
                .isLessThanOrEqualTo(30.0);
    }

    @Test
    @DisplayName("one risk type's standing charges do not reach another")
    void scopedToTheirType() {
        entry(RiskType.REQUIREMENT_CHANGE, 8, BEFORE, true);

        assertThat(ledger().netSince(RiskType.OVER_BUDGET, SCORED_AT)).isZero();
        assertThat(ledger().netSince(RiskType.COMMUNICATION_COLLABORATION, SCORED_AT)).isZero();
    }

    @Nested
    @DisplayName("Pressing Assess")
    class Carrying {

        /** The next scoring, after everything above was written. */
        private final Instant rescoredAt = AFTER.plus(Duration.ofHours(1));

        private void carry(RiskType type, Instant previousScoring) {
            new SprintRiskAdjustmentService(repository()).carryThroughScoring(
                    UUID.randomUUID(), type, previousScoring);
        }

        @Test
        @DisplayName("keeps what the board added and took off since the last scoring")
        void keepsTheMovement() {
            entry(RiskType.OVER_BUDGET, 8, AFTER, false);   // a card blocked
            entry(RiskType.OVER_BUDGET, -3, AFTER, false);  // a task done on time

            carry(RiskType.OVER_BUDGET, SCORED_AT);

            // Without the carry the new cutoff swallowed both and the gauge fell straight
            // back to the model's figure — the button appeared to undo the board.
            assertThat(ledger().netSince(RiskType.OVER_BUDGET, rescoredAt))
                    .isEqualByComparingTo(BigDecimal.valueOf(5));
        }

        @Test
        @DisplayName("does not revive what an earlier scoring already folded away")
        void leavesOldHistoryAlone() {
            entry(RiskType.OVER_BUDGET, 8, BEFORE, false);

            carry(RiskType.OVER_BUDGET, SCORED_AT);

            assertThat(ledger().netSince(RiskType.OVER_BUDGET, rescoredAt)).isZero();
        }

        @Test
        @DisplayName("touches only the risk being scored")
        void scopedToTheScoredType() {
            entry(RiskType.COMMUNICATION_COLLABORATION, 4, AFTER, false);

            carry(RiskType.OVER_BUDGET, SCORED_AT);

            assertThat(ledger().netSince(RiskType.COMMUNICATION_COLLABORATION, rescoredAt)).isZero();
        }

        @Test
        @DisplayName("a sprint never scored before carries everything it has")
        void firstScoringCarriesAll() {
            entry(RiskType.OVER_BUDGET, 8, BEFORE, false);

            carry(RiskType.OVER_BUDGET, null);

            assertThat(ledger().netSince(RiskType.OVER_BUDGET, rescoredAt))
                    .isEqualByComparingTo(BigDecimal.valueOf(8));
        }

        /**
         * The two repository calls the carry makes, over this test's own entries. A proxy
         * rather than a mock: the inline mock maker does not run on the JDK this builds on.
         */
        private SprintRiskAdjustmentRepository repository() {
            return (SprintRiskAdjustmentRepository) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {SprintRiskAdjustmentRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findBySprintIdOrderByCreatedAtDesc" -> List.copyOf(entries);
                        case "saveAll" -> args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
