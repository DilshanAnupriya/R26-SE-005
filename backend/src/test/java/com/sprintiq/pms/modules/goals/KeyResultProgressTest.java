package com.sprintiq.pms.modules.goals;

import com.sprintiq.pms.modules.goals.domain.Goal;
import com.sprintiq.pms.modules.goals.domain.GoalStatus;
import com.sprintiq.pms.modules.goals.domain.KeyResult;
import com.sprintiq.pms.modules.goals.domain.MetricDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The progress arithmetic, which is where an OKR implementation is usually wrong.
 *
 * <p>These are plain unit tests — no Spring, no database. The maths is pure and the
 * cases that matter are all about numbers, so a container would add thirty seconds
 * and prove nothing extra.</p>
 */
@DisplayName("Key result progress")
class KeyResultProgressTest {

    private static KeyResult keyResult(double start, double current, double target,
                                       MetricDirection direction) {
        KeyResult result = KeyResult.builder()
                .goalId(java.util.UUID.randomUUID())
                .title("A measure")
                .startValue(BigDecimal.valueOf(start))
                .currentValue(BigDecimal.valueOf(current))
                .targetValue(BigDecimal.valueOf(target))
                .direction(direction)
                .build();
        return result;
    }

    @Nested
    @DisplayName("increasing metrics")
    class Increasing {

        @Test
        @DisplayName("measures the distance travelled, not the fraction of the target")
        void measuresTravelled() {
            // 40 -> 60 with a target of 80: two thirds of the journey, not 75% of 80.
            KeyResult result = keyResult(40, 60, 80, MetricDirection.INCREASE);
            assertThat(result.progressPct()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("reports nothing done when it has not moved")
        void noMovement() {
            assertThat(keyResult(10, 10, 50, MetricDirection.INCREASE).progressPct())
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("caps at 100 when overachieved")
        void capsAtHundred() {
            // Otherwise one runaway result hides a failing sibling when the goal averages them.
            KeyResult result = keyResult(0, 150, 100, MetricDirection.INCREASE);
            assertThat(result.progressPct()).isEqualByComparingTo("100.00");
            assertThat(result.isAchieved()).isTrue();
        }

        @Test
        @DisplayName("floors at 0 when moving the wrong way")
        void floorsAtZero() {
            assertThat(keyResult(50, 30, 100, MetricDirection.INCREASE).progressPct())
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("decreasing metrics")
    class Decreasing {

        @Test
        @DisplayName("counts going down as progress")
        void downIsProgress() {
            // Defects 40 -> 10 against a target of 0. Dividing current by target would
            // report this genuine improvement as failure.
            KeyResult result = keyResult(40, 10, 0, MetricDirection.DECREASE);
            assertThat(result.progressPct()).isEqualByComparingTo("75.00");
        }

        @Test
        @DisplayName("is achieved once it reaches the target from above")
        void achievedFromAbove() {
            assertThat(keyResult(40, 5, 10, MetricDirection.DECREASE).isAchieved()).isTrue();
            assertThat(keyResult(40, 15, 10, MetricDirection.DECREASE).isAchieved()).isFalse();
        }

        @Test
        @DisplayName("floors at 0 when the number climbs")
        void climbingFloors() {
            assertThat(keyResult(40, 55, 10, MetricDirection.DECREASE).progressPct())
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("recording a value")
    class Recording {

        @Test
        @DisplayName("judges progress against elapsed time, not against progress alone")
        void pacedAgainstTheCalendar() {
            KeyResult result = keyResult(0, 0, 100, MetricDirection.INCREASE);
            BigDecimal halfwayThroughTheCycle = BigDecimal.valueOf(50);

            // 45% done at the halfway point is fine — 5 points behind pace.
            result.recordValue(BigDecimal.valueOf(45), halfwayThroughTheCycle);
            assertThat(result.getStatus()).isEqualTo(GoalStatus.ON_TRACK);

            // 30% at halfway is 20 behind: worth flagging, still recoverable.
            result.recordValue(BigDecimal.valueOf(30), halfwayThroughTheCycle);
            assertThat(result.getStatus()).isEqualTo(GoalStatus.AT_RISK);

            // 5% at halfway is 45 behind.
            result.recordValue(BigDecimal.valueOf(5), halfwayThroughTheCycle);
            assertThat(result.getStatus()).isEqualTo(GoalStatus.OFF_TRACK);

            result.recordValue(BigDecimal.valueOf(100), halfwayThroughTheCycle);
            assertThat(result.getStatus()).isEqualTo(GoalStatus.ACHIEVED);
        }

        @Test
        @DisplayName("does not call a brand-new key result off track on day one")
        void dayOneIsNotFailing() {
            // Every OKR is at 0% when the quarter opens. Marking that OFF_TRACK
            // paints every new goal red and teaches everyone to ignore the colour.
            KeyResult result = keyResult(0, 0, 100, MetricDirection.INCREASE);
            result.recordValue(BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(result.getStatus()).isEqualTo(GoalStatus.ON_TRACK);
        }

        @Test
        @DisplayName("does call it off track when the cycle is nearly over and nothing moved")
        void lateAndUnmoved() {
            KeyResult result = keyResult(0, 0, 100, MetricDirection.INCREASE);
            result.recordValue(BigDecimal.ZERO, BigDecimal.valueOf(90));
            assertThat(result.getStatus()).isEqualTo(GoalStatus.OFF_TRACK);
        }
    }

    @Nested
    @DisplayName("goal roll-up")
    class RollUp {

        private static Goal dated(LocalDate start, LocalDate target) {
            return Goal.builder().title("Ship onboarding").startDate(start).targetDate(target).build();
        }

        @Test
        @DisplayName("averages its key results")
        void averages() {
            Goal goal = Goal.builder().title("Ship onboarding").build();
            goal.recalculateFrom(List.of(
                    keyResult(0, 100, 100, MetricDirection.INCREASE), // 100%
                    keyResult(0, 20, 100, MetricDirection.INCREASE))); // 20%

            assertThat(goal.getProgressPct()).isEqualByComparingTo("60.00");
        }

        @Test
        @DisplayName("expected progress tracks the calendar, not the work")
        void expectedProgress() {
            Goal goal = dated(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 11));

            assertThat(goal.expectedProgressPct(LocalDate.of(2026, 1, 1))).isEqualByComparingTo("0.00");
            assertThat(goal.expectedProgressPct(LocalDate.of(2026, 1, 6))).isEqualByComparingTo("50.00");
            assertThat(goal.expectedProgressPct(LocalDate.of(2026, 1, 11))).isEqualByComparingTo("100");
            // Past the end it stays at 100 rather than running away.
            assertThat(goal.expectedProgressPct(LocalDate.of(2026, 2, 1))).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("is on track at 0% on the first day of the cycle")
        void dayOneIsOnTrack() {
            Goal goal = dated(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
            goal.recalculateFrom(
                    List.of(keyResult(0, 0, 100, MetricDirection.INCREASE)),
                    LocalDate.of(2026, 1, 1));

            assertThat(goal.getProgressPct()).isEqualByComparingTo("0.00");
            assertThat(goal.getStatus()).isEqualTo(GoalStatus.ON_TRACK);
        }

        @Test
        @DisplayName("is off track at 0% with the cycle nearly over")
        void lateAndUnmovedIsOffTrack() {
            Goal goal = dated(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
            goal.recalculateFrom(
                    List.of(keyResult(0, 0, 100, MetricDirection.INCREASE)),
                    LocalDate.of(2026, 3, 25));

            assertThat(goal.getStatus()).isEqualTo(GoalStatus.OFF_TRACK);
        }

        @Test
        @DisplayName("a goal without dates is only flagged when nothing has moved")
        void undatedFallback() {
            Goal goal = Goal.builder().title("No dates").build();

            goal.recalculateFrom(List.of(keyResult(0, 0, 100, MetricDirection.INCREASE)));
            assertThat(goal.getStatus()).isEqualTo(GoalStatus.AT_RISK);

            goal.recalculateFrom(List.of(keyResult(0, 30, 100, MetricDirection.INCREASE)));
            assertThat(goal.getStatus()).isEqualTo(GoalStatus.ON_TRACK);
        }

        @Test
        @DisplayName("is achieved only when every key result is")
        void achievedRequiresAll() {
            Goal goal = Goal.builder().title("Ship onboarding").build();
            goal.recalculateFrom(List.of(
                    keyResult(0, 100, 100, MetricDirection.INCREASE),
                    keyResult(0, 100, 100, MetricDirection.INCREASE)));

            assertThat(goal.getStatus()).isEqualTo(GoalStatus.ACHIEVED);
        }

        @Test
        @DisplayName("keeps a closed verdict rather than letting a late number rewrite it")
        void closedVerdictSticks() {
            Goal goal = Goal.builder().title("Last quarter").status(GoalStatus.MISSED).build();
            goal.recalculateFrom(List.of(keyResult(0, 100, 100, MetricDirection.INCREASE)));

            // Progress still updates — the number is the number — but the verdict stands.
            assertThat(goal.getProgressPct()).isEqualByComparingTo("100.00");
            assertThat(goal.getStatus()).isEqualTo(GoalStatus.MISSED);
        }

        @Test
        @DisplayName("reports zero for an objective with no key results yet")
        void noKeyResults() {
            Goal goal = Goal.builder().title("Just drafted").build();
            goal.recalculateFrom(List.of());
            assertThat(goal.getProgressPct()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("drifting against the calendar")
    class Drift {

        private static Goal quarterlyGoal() {
            return Goal.builder()
                    .title("Ship onboarding")
                    .startDate(LocalDate.of(2026, 1, 1))
                    .targetDate(LocalDate.of(2026, 3, 31))
                    .build();
        }

        @Test
        @DisplayName("the same numbers slide from on track to off track as the cycle burns")
        void sameNumbersDegrade() {
            // The defect this guards: status was only recomputed on a write, so a goal
            // nobody checked into kept the verdict from its last check-in for months.
            Goal goal = quarterlyGoal();
            List<KeyResult> results = List.of(keyResult(0, 20, 100, MetricDirection.INCREASE));

            goal.recalculateFrom(results, LocalDate.of(2026, 1, 20));
            assertThat(goal.getStatus()).isEqualTo(GoalStatus.ON_TRACK);

            // Nothing moved; only the calendar did.
            assertThat(goal.statusOn(results, LocalDate.of(2026, 2, 15)))
                    .isEqualTo(GoalStatus.AT_RISK);
            assertThat(goal.statusOn(results, LocalDate.of(2026, 3, 20)))
                    .isEqualTo(GoalStatus.OFF_TRACK);
        }

        @Test
        @DisplayName("asking is not answering — statusOn leaves the goal alone")
        void statusOnIsPure() {
            Goal goal = quarterlyGoal();
            List<KeyResult> results = List.of(keyResult(0, 20, 100, MetricDirection.INCREASE));
            goal.recalculateFrom(results, LocalDate.of(2026, 1, 20));

            goal.statusOn(results, LocalDate.of(2026, 3, 20));

            assertThat(goal.getStatus()).isEqualTo(GoalStatus.ON_TRACK);
            assertThat(goal.getProgressPct()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("a closed verdict does not drift")
        void closedDoesNotDrift() {
            Goal goal = Goal.builder()
                    .title("Last quarter")
                    .status(GoalStatus.ACHIEVED)
                    .startDate(LocalDate.of(2026, 1, 1))
                    .targetDate(LocalDate.of(2026, 3, 31))
                    .build();

            assertThat(goal.statusOn(
                    List.of(keyResult(0, 0, 100, MetricDirection.INCREASE)),
                    LocalDate.of(2026, 3, 30)))
                    .isEqualTo(GoalStatus.ACHIEVED);
        }
    }
}
