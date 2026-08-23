package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.RiskSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Weighting three risk models into one sprint figure.
 *
 * <p>Every test here is about what happens when the evidence is <em>incomplete</em>,
 * because that is the only part of a weighted average that is easy to get wrong in a
 * way nobody notices. Getting it wrong in the obvious direction — treating an unscored
 * model as a zero — makes the least-understood sprint look like the safest one on the
 * board.</p>
 */
@DisplayName("Overall risk aggregation")
class OverallRiskAggregationTest {

    private final RiskService service = new RiskService(null, null, null, null, null, null, null, null);

    private static BigDecimal score(double value) {
        return BigDecimal.valueOf(value);
    }

    @Test
    @DisplayName("weights the three models 40/30/30 when all of them scored")
    void weightsAllThree() {
        // 0.40*0.50 + 0.30*0.50 + 0.30*0.50 = 0.50
        var overall = service.aggregateScores(score(0.50), score(0.50), score(0.50), false);

        assertThat(overall.score()).isEqualByComparingTo("0.5000");
        assertThat(overall.degraded()).isFalse();
        assertThat(overall.weights())
                .containsEntry("overBudget", new BigDecimal("0.40"))
                .containsEntry("requirementChange", new BigDecimal("0.30"))
                .containsEntry("communicationCollaboration", new BigDecimal("0.30"));
    }

    @Test
    @DisplayName("over-budget carries more weight than the other two")
    void overBudgetDominates() {
        // Only over-budget is alarmed; the other two are calm.
        var obHigh = service.aggregateScores(score(1.0), score(0.0), score(0.0), false);
        // Only collaboration is alarmed.
        var ccHigh = service.aggregateScores(score(0.0), score(0.0), score(1.0), false);

        assertThat(obHigh.score()).isGreaterThan(ccHigh.score());
    }

    @Test
    @DisplayName("an unscored model contributes no weight — it is not counted as a zero")
    void unscoredModelIsNotAZero() {
        // The regression this exists for. Two models are screaming; the third could not
        // be scored. Counting the gap as 0.0 would drag the average down to 0.47 and
        // report a calmer sprint than the one where all three agree it is on fire.
        var partial = service.aggregateScores(score(0.90), score(0.80), null, false);

        // 0.40*0.90 + 0.30*0.80 renormalised over 0.70 = 0.857…
        assertThat(partial.score()).isGreaterThan(score(0.80));
        assertThat(partial.weights())
                .containsEntry("communicationCollaboration", new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("renormalised weights still sum to one")
    void weightsAlwaysSumToOne() {
        var partial = service.aggregateScores(score(0.5), null, score(0.5), false);

        BigDecimal sum = partial.weights().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("a missing model bumps the level one band up")
    void missingModelBumpsTheLevel() {
        // 0.50 alone would band as MEDIUM. Built on two models out of three, it is
        // shown as HIGH: uncertainty is rounded toward caution, never away.
        var partial = service.aggregateScores(score(0.50), score(0.50), null, false);

        assertThat(RiskSeverity.forScore(partial.score())).isEqualTo(RiskSeverity.MEDIUM);
        assertThat(partial.level()).isEqualTo(RiskSeverity.HIGH);
        assertThat(partial.degraded()).isTrue();
    }

    @Test
    @DisplayName("a degraded model bumps the level even when all three scored")
    void degradedBumpsTheLevel() {
        var complete = service.aggregateScores(score(0.50), score(0.50), score(0.50), false);
        var degraded = service.aggregateScores(score(0.50), score(0.50), score(0.50), true);

        assertThat(complete.level()).isEqualTo(RiskSeverity.MEDIUM);
        assertThat(degraded.level()).isEqualTo(RiskSeverity.HIGH);
        assertThat(degraded.score()).isEqualByComparingTo(complete.score());
    }

    @Test
    @DisplayName("the bump stops at CRITICAL rather than falling off the end")
    void bumpSaturatesAtCritical() {
        var extreme = service.aggregateScores(score(0.99), score(0.99), null, false);

        assertThat(extreme.level()).isEqualTo(RiskSeverity.CRITICAL);
    }

    @Test
    @DisplayName("nothing scored means no figure at all, not a reassuring zero")
    void nothingScoredReturnsNull() {
        assertThat(service.aggregateScores(null, null, null, false)).isNull();
    }

    @Test
    @DisplayName("says in words why the level sits above the number")
    void rationaleExplainsTheBump() {
        var partial = service.aggregateScores(score(0.50), score(0.50), null, false);

        assertThat(partial.rationale())
                .contains("renormalised")
                .contains("one band above the number");
    }
}
