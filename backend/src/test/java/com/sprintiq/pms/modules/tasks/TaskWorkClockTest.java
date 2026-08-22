package com.sprintiq.pms.modules.tasks;

import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The work clock is a stopwatch, not a deadline, and every one of these tests pins a
 * case where the obvious implementation — {@code createdAt + estimate} — gets it wrong.
 */
@DisplayName("Task work clock")
class TaskWorkClockTest {

    private static Task open(long banked, Instant runningSince) {
        return Task.builder()
                .status(TaskStatus.IN_PROGRESS)
                .elapsedSeconds(banked)
                .runningSince(runningSince)
                .build();
    }

    @Nested
    @DisplayName("crossing into a finished column")
    class Freezing {

        @Test
        @DisplayName("banks the current run and stops")
        void banksTheRun() {
            Instant startedAnHourAgo = Instant.now().minus(Duration.ofHours(1));
            Task task = open(0, startedAnHourAgo);

            task.transitionTo(TaskStatus.DONE);

            assertThat(task.getRunningSince()).isNull();
            assertThat(task.getElapsedSeconds()).isBetween(3595L, 3605L);
        }

        @Test
        @DisplayName("keeps the total still while the task sits there")
        void staysFrozen() {
            Task task = open(0, Instant.now().minus(Duration.ofHours(1)));
            task.transitionTo(TaskStatus.DONE);

            long atCompletion = task.elapsedSecondsAt(Instant.now());
            long aFortnightLater = task.elapsedSecondsAt(Instant.now().plus(Duration.ofDays(14)));

            // The whole reason the clock is stored rather than derived: two weeks in Done
            // must not burn two weeks of the estimate.
            assertThat(aFortnightLater).isEqualTo(atCompletion);
        }

        @Test
        @DisplayName("CANCELLED stops it too — nobody is working on it either")
        void cancelledAlsoFreezes() {
            Task task = open(120, Instant.now());
            task.transitionTo(TaskStatus.CANCELLED);
            assertThat(task.getRunningSince()).isNull();
        }
    }

    @Nested
    @DisplayName("coming back out")
    class Resuming {

        @Test
        @DisplayName("resumes from the banked total rather than restarting")
        void resumesFromBanked() {
            Task task = open(0, Instant.now().minus(Duration.ofHours(2)));
            task.transitionTo(TaskStatus.DONE);
            long banked = task.getElapsedSeconds();

            task.transitionTo(TaskStatus.IN_REVIEW);

            assertThat(task.getElapsedSeconds()).isEqualTo(banked);
            assertThat(task.getRunningSince()).isNotNull();
            assertThat(task.elapsedSecondsAt(Instant.now())).isGreaterThanOrEqualTo(banked);
        }

        @Test
        @DisplayName("does not charge the task for the time it spent finished")
        void doesNotChargeForTimeInDone() {
            Task task = open(3600, null);
            task.setStatus(TaskStatus.DONE);

            task.transitionTo(TaskStatus.TODO);

            // A day in Done then reopened: still one hour of work, not twenty-five.
            assertThat(task.elapsedSecondsAt(Instant.now())).isBetween(3600L, 3605L);
        }

        @Test
        @DisplayName("counts as a reopen, which is what the collaboration model reads")
        void countsAsReopen() {
            Task task = open(60, null);
            task.setStatus(TaskStatus.DONE);
            task.transitionTo(TaskStatus.IN_REVIEW);
            assertThat(task.getReopenedCount()).isEqualTo((short) 1);
        }
    }

    @Nested
    @DisplayName("moving between two open columns")
    class StayingOpen {

        @Test
        @DisplayName("leaves the clock alone")
        void keepsRunning() {
            Instant since = Instant.now().minus(Duration.ofMinutes(30));
            Task task = open(0, since);

            task.transitionTo(TaskStatus.IN_REVIEW);

            assertThat(task.getRunningSince()).isEqualTo(since);
            assertThat(task.getElapsedSeconds()).isZero();
        }

        @Test
        @DisplayName("BLOCKED keeps counting — a blocked task is still costing the sprint time")
        void blockedKeepsCounting() {
            Instant since = Instant.now().minus(Duration.ofMinutes(30));
            Task task = open(0, since);
            task.transitionTo(TaskStatus.BLOCKED);
            assertThat(task.getRunningSince()).isEqualTo(since);
        }
    }

    @Nested
    @DisplayName("against the estimate")
    class Overdue {

        @Test
        @DisplayName("an unestimated task is never overdue")
        void unestimatedIsNeverOverdue() {
            Task task = open(999_999, null);
            assertThat(task.isOverdueAgainstEstimate(Instant.now())).isFalse();
        }

        @Test
        @DisplayName("inside the estimate is not overdue")
        void insideEstimate() {
            Task task = open(1800, null);
            task.setEstimateMinutes(60);
            assertThat(task.isOverdueAgainstEstimate(Instant.now())).isFalse();
        }

        @Test
        @DisplayName("past the estimate is overdue, whatever was logged against it")
        void pastEstimate() {
            Task task = open(7200, null);
            task.setEstimateMinutes(60);
            // Deliberately zero logged effort: the wall-clock question and the
            // logged-effort question are different, and this is the wall-clock one.
            task.setSpentMinutes(0);
            assertThat(task.isOverdueAgainstEstimate(Instant.now())).isTrue();
            assertThat(task.isOverEstimate()).isFalse();
        }
    }
}
