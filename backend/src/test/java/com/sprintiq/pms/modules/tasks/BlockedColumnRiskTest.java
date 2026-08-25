package com.sprintiq.pms.modules.tasks;

import com.sprintiq.pms.modules.ai.application.SprintRiskAdjustmentService;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.tasks.application.TaskRiskReporter;
import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What dragging a card into the Blocked column costs the sprint.
 *
 * <p>The charge itself is not new — {@code TaskStatus.BLOCKED} and the over-budget
 * adjustment behind it have been in the delivery core since the beginning. What was new
 * is that a board now has a column for it, so the transition is reachable by dragging
 * rather than only through the task dialog. That makes this the path a team will
 * actually take, and it had no test.</p>
 *
 * <p>The ledger is a hand-written recorder rather than a mock, following
 * {@code TaskReopenRiskTest}: the assertions want the arguments themselves, and it keeps
 * the test off the inline mock maker, which the JDK this builds on does not support.</p>
 */
@DisplayName("The Blocked column as risk")
class BlockedColumnRiskTest {

    private static final UUID SPRINT = UUID.randomUUID();

    private Recorder ledger;
    private TaskRiskReporter reporter;

    @BeforeEach
    void setUp() {
        ledger = new Recorder();
        reporter = new TaskRiskReporter(ledger);
    }

    @Nested
    @DisplayName("Blocking")
    class Blocking {

        @Test
        @DisplayName("charges over-budget, and says which task")
        void chargesOverBudget() {
            reporter.statusChanged(task(TaskStatus.BLOCKED, 480), TaskStatus.IN_PROGRESS);

            assertThat(ledger.entries).hasSize(1);
            Entry entry = ledger.entries.get(0);
            assertThat(entry.riskType()).isEqualTo(RiskType.OVER_BUDGET);
            assertThat(entry.delta()).isPositive();
            assertThat(entry.reason()).contains("Task blocked").contains("MBA-1");
        }

        @Test
        @DisplayName("a bigger task blocked costs the sprint more")
        void scalesWithTheEstimate() {
            reporter.statusChanged(task(TaskStatus.BLOCKED, 120), TaskStatus.TODO);
            BigDecimal small = ledger.entries.get(0).delta();

            ledger.entries.clear();
            reporter.statusChanged(task(TaskStatus.BLOCKED, 480), TaskStatus.TODO);
            BigDecimal aDay = ledger.entries.get(0).delta();

            // Half a day's work stuck is not the same news as two days' work stuck, and a
            // flat charge would report them identically.
            assertThat(aDay).isGreaterThan(small);
        }

        @Test
        @DisplayName("one very large task cannot dominate the score on its own")
        void isCapped() {
            // Weight is bounded at 2.0 and the charge at 10, so a fortnight-long task and
            // a two-day one are charged the same. Without it a single mis-estimated card
            // could pin the sprint at critical by being dragged one column.
            reporter.statusChanged(task(TaskStatus.BLOCKED, 60 * 80), TaskStatus.TODO);

            assertThat(ledger.entries.get(0).delta()).isLessThanOrEqualTo(BigDecimal.valueOf(10));
        }

        @Test
        @DisplayName("an unestimated task still counts for something")
        void unestimatedStillCharges() {
            reporter.statusChanged(task(TaskStatus.BLOCKED, null), TaskStatus.TODO);

            // A task nobody sized is not a task that cannot go wrong, so it is charged at
            // the baseline weight rather than skipped.
            assertThat(ledger.entries.get(0).delta()).isPositive();
        }
    }

    @Nested
    @DisplayName("Unblocking")
    class Unblocking {

        @Test
        @DisplayName("gives back exactly what blocking took")
        void reversesTheCharge() {
            Task task = task(TaskStatus.BLOCKED, 480);
            reporter.statusChanged(task, TaskStatus.IN_PROGRESS);
            BigDecimal charged = ledger.entries.get(0).delta();

            ledger.entries.clear();
            Task freed = task(TaskStatus.IN_PROGRESS, 480);
            reporter.statusChanged(freed, TaskStatus.BLOCKED);

            // Symmetric on purpose: a sprint that gets its blockers cleared has to end up
            // back where it started, or the ledger only ever drifts upward and a team
            // that fixed the problem still reads as one that did not.
            assertThat(ledger.entries.get(0).delta()).isEqualByComparingTo(charged.negate());
            assertThat(ledger.entries.get(0).reason()).contains("Task unblocked");
        }

        @Test
        @DisplayName("dragging within the unblocked columns charges nothing either way")
        void ordinaryMovesAreFree() {
            reporter.statusChanged(task(TaskStatus.IN_REVIEW, 480), TaskStatus.IN_PROGRESS);

            assertThat(ledger.entries)
                    .as("a card moving between ordinary columns is not evidence about risk")
                    .noneMatch(entry -> entry.reason().contains("block"));
        }
    }

    // ------------------------------------------------------------------- fixtures

    private static Task task(TaskStatus status, Integer estimateMinutes) {
        return Task.builder()
                .sprintId(SPRINT)
                .itemKey("MBA-1")
                .title("Wire the payment callback")
                .status(status)
                .estimateMinutes(estimateMinutes)
                .build();
    }

    /** One ledger write, flattened to what the assertions read. */
    private record Entry(RiskType riskType, BigDecimal delta, String reason) {}

    private static final class Recorder extends SprintRiskAdjustmentService {

        private final List<Entry> entries = new ArrayList<>();

        Recorder() {
            // The repository is never reached: every method the reporter calls is
            // overridden here.
            super(null);
        }

        @Override
        public void record(UUID sprintId, RiskType riskType, BigDecimal delta, String reason) {
            entries.add(new Entry(riskType, delta, reason));
        }

        @Override
        public void revise(UUID sprintId, RiskType riskType, String sourceKey,
                           BigDecimal delta, String reason) {
            entries.add(new Entry(riskType, delta, reason));
        }

        @Override
        public void recordOnce(UUID sprintId, RiskType riskType, String sourceKey,
                              BigDecimal delta, String reason) {
            entries.add(new Entry(riskType, delta, reason));
        }

        @Override
        public void clear(UUID sprintId, String sourceKey) {
            // Nothing to record: the assertions here are about what was charged.
        }
    }
}
