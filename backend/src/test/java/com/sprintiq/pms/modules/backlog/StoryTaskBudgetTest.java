package com.sprintiq.pms.modules.backlog;

import com.sprintiq.pms.modules.backlog.domain.StoryTaskBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning story points into a task budget.
 *
 * <p>The arithmetic is trivial; the edge cases are not. Most of these pin what happens
 * when a story has no estimate, because the tempting shortcut — treating unsized as a
 * budget of zero — makes every unpointed story permanently over budget and would push
 * sprint risk up for work nobody has even sized yet.</p>
 */
@DisplayName("Story task budget")
class StoryTaskBudgetTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** The user's example: 5 points at one hour a point. */
    private static StoryTaskBudget fivePointStory(long consumedMinutes, long tasks) {
        return StoryTaskBudget.of(bd("5"), BigDecimal.ONE, consumedMinutes, tasks, 0);
    }

    @Nested
    @DisplayName("the worked example")
    class WorkedExample {

        @Test
        @DisplayName("a 5-point story is worth 5 hours before any task is added")
        void fivePointsIsFiveHours() {
            StoryTaskBudget budget = fivePointStory(0, 0);

            assertThat(budget.budgetMinutes()).isEqualTo(300);
            assertThat(budget.remainingMinutes()).isEqualTo(300);
            assertThat(budget.isOverBudget()).isFalse();
        }

        @Test
        @DisplayName("a 4-hour task leaves 1 hour")
        void firstTaskLeavesTheRemainder() {
            StoryTaskBudget budget = fivePointStory(240, 1);

            assertThat(budget.remainingMinutes()).isEqualTo(60);
            assertThat(budget.isOverBudget()).isFalse();
        }

        @Test
        @DisplayName("a 2-hour task against 1 hour left is an hour over")
        void secondTaskOverruns() {
            // 240 + 120 against a 300 budget.
            StoryTaskBudget budget = fivePointStory(360, 2);

            assertThat(budget.remainingMinutes()).isEqualTo(-60);
            assertThat(budget.overrunMinutes()).isEqualTo(60);
            assertThat(budget.isOverBudget()).isTrue();
        }

        @Test
        @DisplayName("warns before the task is saved, not after")
        void wouldExceedAnswersBeforehand() {
            StoryTaskBudget afterFirstTask = fivePointStory(240, 1);

            assertThat(afterFirstTask.wouldExceed(60)).isFalse();   // exactly fills it
            assertThat(afterFirstTask.wouldExceed(120)).isTrue();   // the 2-hour task
        }
    }

    @Nested
    @DisplayName("conversion rate")
    class Rate {

        @Test
        @DisplayName("respects a project whose point is worth more than an hour")
        void honoursTheProjectRate() {
            StoryTaskBudget budget = StoryTaskBudget.of(bd("3"), bd("6.5"), 0, 0, 0);

            // 3 × 6.5h = 19.5h = 1170 minutes.
            assertThat(budget.budgetMinutes()).isEqualTo(1170);
        }

        @Test
        @DisplayName("rounds a fractional estimate to whole minutes")
        void roundsToMinutes() {
            StoryTaskBudget budget = StoryTaskBudget.of(bd("0.5"), bd("1.33"), 0, 0, 0);

            // 0.5 × 1.33h × 60 = 39.9 → 40
            assertThat(budget.budgetMinutes()).isEqualTo(40);
        }
    }

    @Nested
    @DisplayName("stories nobody has sized")
    class Unsized {

        @Test
        @DisplayName("has no budget rather than a budget of zero")
        void unpointedHasNoBudget() {
            // The trap. Zero would make every unsized story permanently over budget and
            // push sprint risk up for work nobody has estimated yet.
            StoryTaskBudget budget = StoryTaskBudget.of(null, BigDecimal.ONE, 480, 2, 0);

            assertThat(budget.budgetMinutes()).isNull();
            assertThat(budget.remainingMinutes()).isNull();
            assertThat(budget.isSized()).isFalse();
            assertThat(budget.isOverBudget()).isFalse();
            assertThat(budget.overrunMinutes()).isZero();
        }

        @Test
        @DisplayName("never warns that a task would exceed a budget it does not have")
        void unsizedNeverWarns() {
            StoryTaskBudget budget = StoryTaskBudget.of(null, BigDecimal.ONE, 0, 0, 0);

            assertThat(budget.wouldExceed(6000)).isFalse();
        }

        @Test
        @DisplayName("reports no ratio, rather than a comfortable zero")
        void unsizedHasNoRatio() {
            assertThat(StoryTaskBudget.of(null, BigDecimal.ONE, 100, 1, 0).consumedRatio())
                    .isNull();
        }

        @Test
        @DisplayName("a zero-point story is treated as unsized, not as no time at all")
        void zeroPointsIsUnsized() {
            assertThat(StoryTaskBudget.of(BigDecimal.ZERO, BigDecimal.ONE, 60, 1, 0).isSized())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("consumed ratio says how far over, not just that it is over")
        void ratioQuantifiesTheOverrun() {
            assertThat(fivePointStory(450, 3).consumedRatio())
                    .isEqualByComparingTo("1.500");
        }

        @Test
        @DisplayName("counts tasks that carry no estimate, so slack is not imagined")
        void unestimatedTasksAreVisible() {
            // Four tasks, two of them unsized: the two hours consumed is a floor, not a
            // measurement, and the caller has to be able to say so.
            StoryTaskBudget budget = StoryTaskBudget.of(bd("5"), BigDecimal.ONE, 120, 4, 2);

            assertThat(budget.unestimatedTasks()).isEqualTo(2);
            assertThat(budget.remainingMinutes()).isEqualTo(180);
        }
    }
}
