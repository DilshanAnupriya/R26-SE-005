package com.sprintiq.pms.modules.ai;

import com.sprintiq.pms.modules.ai.domain.CostOverrun;
import com.sprintiq.pms.modules.ai.domain.CostOverrun.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a cost forecast is far enough over budget to count as risk.
 *
 * <p>The margin is the point of these. A cost model with a 23.5% typical error cannot
 * tell a sprint 20% over budget from one that is exactly on plan, so charging risk at
 * that variance would be charging for the model's own imprecision. Half of these pin the
 * cases where nothing should happen.</p>
 */
@DisplayName("Cost overrun")
class CostOverrunTest {

    private static final double MARGIN = 23.5;
    private static final double PCT_PER_POINT = 5.0;
    private static final double MIN = 2.0;
    private static final double MAX = 12.0;

    private static CostOverrun evaluate(String forecast, String budget, String variancePct) {
        return CostOverrun.evaluate(
                forecast == null ? null : new BigDecimal(forecast),
                budget == null ? null : new BigDecimal(budget),
                variancePct == null ? null : new BigDecimal(variancePct),
                MARGIN, PCT_PER_POINT, MIN, MAX);
    }

    @Test
    @DisplayName("does not charge an overrun inside the model's own error")
    void withinMarginIsNotCharged() {
        // The real reading from Sprint 24: $3,059 against a $2,500 budget is 22.4% over,
        // and the model is typically 23.5% out. Indistinguishable from being on budget.
        CostOverrun overrun = evaluate("3059.33", "2500", "22.37");

        assertThat(overrun.outcome()).isEqualTo(Outcome.WITHIN_MARGIN);
        assertThat(overrun.delta()).isEqualByComparingTo("0");
        assertThat(overrun.isCharged()).isFalse();
    }

    @Test
    @DisplayName("charges once the overrun exceeds what the model could explain away")
    void beyondMarginIsCharged() {
        // $3,800 against $2,500 is 52% over: 28.5 points beyond the margin.
        CostOverrun overrun = evaluate("3800", "2500", "52.0");

        assertThat(overrun.outcome()).isEqualTo(Outcome.CHARGED);
        assertThat(overrun.beyondMarginPct()).isEqualByComparingTo("28.5");
        // 28.5 / 5 = 5.7
        assertThat(overrun.delta()).isEqualByComparingTo("5.7");
    }

    @Test
    @DisplayName("charges more as the miss grows")
    void deltaScalesWithTheMiss() {
        BigDecimal smaller = evaluate("3800", "2500", "52.0").delta();
        BigDecimal bigger = evaluate("5000", "2500", "100.0").delta();

        assertThat(bigger).isGreaterThan(smaller);
    }

    @Test
    @DisplayName("caps the charge so one sprint cannot swamp the ledger")
    void deltaIsCapped() {
        assertThat(evaluate("50000", "2500", "1900").delta())
                .isEqualByComparingTo(BigDecimal.valueOf(MAX));
    }

    @Test
    @DisplayName("never charges less than the floor once it charges at all")
    void deltaHasAFloor() {
        // A hair over the margin still means the plan is wrong; it should register.
        CostOverrun overrun = evaluate("3000", "2500", "24.0");

        assertThat(overrun.isCharged()).isTrue();
        assertThat(overrun.delta()).isEqualByComparingTo(BigDecimal.valueOf(MIN));
    }

    @Test
    @DisplayName("a sprint under budget is not charged")
    void underBudgetIsNotCharged() {
        assertThat(evaluate("2000", "2500", "-20.0").outcome())
                .isEqualTo(Outcome.WITHIN_MARGIN);
    }

    @Test
    @DisplayName("no budget is its own answer, not a clean bill")
    void missingBudgetIsNotZeroRisk() {
        // The state every sprint in this system was in until a budget was set. Reporting
        // it as "on budget" would be inventing a comparison that was never made.
        CostOverrun overrun = evaluate("3059.33", null, null);

        assertThat(overrun.outcome()).isEqualTo(Outcome.NO_BUDGET);
        assertThat(overrun.isCharged()).isFalse();
        assertThat(overrun.delta()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a zero budget is treated as unset rather than as no money at all")
    void zeroBudgetIsUnset() {
        assertThat(evaluate("3000", "0", "0").outcome()).isEqualTo(Outcome.NO_BUDGET);
    }

    @Test
    @DisplayName("no forecast is its own answer too")
    void missingForecastIsReported() {
        assertThat(evaluate(null, "2500", null).outcome()).isEqualTo(Outcome.NO_FORECAST);
    }
}
