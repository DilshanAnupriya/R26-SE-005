package com.sprintiq.pms.modules.ai.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The soft cap on accumulated risk points.
 *
 * <p>Worth its own test because the property that matters is not "it is bounded" — a
 * hard clamp is bounded and was the wrong answer. It is that the total keeps moving.
 * A sprint that has banked its ceiling and then has another task reopened must still
 * see the number change, or the UI is promising something the arithmetic does not do.</p>
 */
@DisplayName("Risk adjustment soft cap")
class RiskAdjustmentSoftCapTest {

    private static double cap(double value) {
        return SprintRiskAdjustmentService.softCap(BigDecimal.valueOf(value)).doubleValue();
    }

    @Test
    @DisplayName("ordinary totals pass through untouched")
    void belowTheKneeIsUnchanged() {
        assertThat(cap(0)).isZero();
        assertThat(cap(8)).isEqualTo(8.0);
        assertThat(cap(20)).isEqualTo(20.0);
        assertThat(cap(-14.5)).isEqualTo(-14.5);
    }

    @Test
    @DisplayName("never exceeds the limit, however much is piled on")
    void neverExceedsTheLimit() {
        assertThat(cap(30)).isLessThan(30.0);
        assertThat(cap(80)).isLessThan(30.0);
        // Far enough out that the increments fall below the four decimals kept, at which
        // point it settles on the limit. Asserted rather than glossed over, because
        // "approaches but never reaches" is true of the curve and not of the arithmetic.
        assertThat(cap(100_000)).isLessThanOrEqualTo(30.0);
    }

    @Test
    @DisplayName("keeps moving past the knee — the reason this is not a clamp")
    void staysStrictlyIncreasing() {
        // 100 points is roughly eight blocked full-day tasks plus a scope addition: past
        // anything a real sprint reaches, and the range over which the number has to go
        // on responding for the UI's promise to be honest.
        double previous = cap(20);
        for (int points = 21; points <= 100; points++) {
            double current = cap(points);
            assertThat(current)
                    .as("total at %d points should exceed the total at %d", points, points - 1)
                    .isGreaterThan(previous);
            previous = current;
        }
    }

    @Test
    @DisplayName("never decreases, even out past the point where it stops rising")
    void isMonotone() {
        double previous = cap(0);
        for (int points = 1; points <= 1_000; points++) {
            double current = cap(points);
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }

    @Test
    @DisplayName("is symmetric — credit saturates the same way pressure does")
    void isSymmetric() {
        assertThat(cap(-45)).isEqualTo(-cap(45));
    }
}
