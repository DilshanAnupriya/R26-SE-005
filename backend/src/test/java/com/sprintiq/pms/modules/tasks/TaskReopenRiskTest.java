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
 * What a reopen costs the sprint.
 *
 * <p>Reopens arrive from two places — a card dragged back out of a finished column, and
 * a card opened for another look — and are charged to two risks with deliberately
 * different curves: collaboration hears the first bounce loudest, over-budget the later
 * ones. These pin the parts that are easy to break by tuning one of the four numbers.</p>
 *
 * <p>The ledger is a hand-written recorder rather than a mock. The reporter's whole job
 * is <em>what</em> it writes, so the assertions want the arguments themselves, and a
 * recorder reads better than five captors — it also keeps the test off the inline mock
 * maker, which the JDK this builds on does not support.</p>
 */
@DisplayName("Reopen risk")
class TaskReopenRiskTest {

    private static final UUID SPRINT = UUID.randomUUID();

    private Recorder ledger;
    private TaskRiskReporter reporter;

    @BeforeEach
    void setUp() {
        ledger = new Recorder();
        reporter = new TaskRiskReporter(ledger);
    }

    /** One `revise` call, flattened to what the assertions actually read. */
    private record Entry(UUID sprintId, RiskType riskType, String sourceKey, BigDecimal delta) {}

    private static final class Recorder extends SprintRiskAdjustmentService {

        private final List<Entry> entries = new ArrayList<>();

        Recorder() {
            // The repository is never reached: every method the reporter calls is
            // overridden here.
            super(null);
        }

        @Override
        public void revise(UUID sprintId, RiskType riskType, String sourceKey,
                           BigDecimal delta, String reason) {
            entries.removeIf(entry -> entry.sourceKey().equals(sourceKey));
            entries.add(new Entry(sprintId, riskType, sourceKey, delta));
        }

        @Override
        public void record(UUID sprintId, RiskType riskType, BigDecimal delta, String reason) {
            entries.add(new Entry(sprintId, riskType, null, delta));
        }

        @Override
        public void clear(UUID sprintId, String sourceKey) {
            entries.removeIf(entry -> sourceKey.equals(entry.sourceKey()));
        }

        BigDecimal charged(RiskType riskType) {
            return entries.stream()
                    .filter(entry -> entry.riskType() == riskType)
                    .map(Entry::delta)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        java.util.List<String> keys() {
            return entries.stream().map(Entry::sourceKey).filter(java.util.Objects::nonNull).toList();
        }

        String keyFor(RiskType riskType) {
            return entries.stream()
                    .filter(entry -> entry.riskType() == riskType)
                    .map(Entry::sourceKey)
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Task task(int reopens, Integer estimateMinutes) {
        Task task = Task.builder()
                .sprintId(SPRINT)
                .itemKey("SIQ-1")
                .status(TaskStatus.IN_REVIEW)
                .estimateMinutes(estimateMinutes)
                .build();
        task.setId(UUID.randomUUID());
        for (int i = 0; i < reopens; i++) {
            task.countReopen();
        }
        return task;
    }

    private BigDecimal chargeFor(int reopens, Integer estimateMinutes, RiskType riskType) {
        Recorder fresh = new Recorder();
        new TaskRiskReporter(fresh).reopened(task(reopens, estimateMinutes));
        return fresh.charged(riskType);
    }

    @Nested
    @DisplayName("counting a reopen")
    class Counting {

        @Test
        @DisplayName("bumps the counter without touching the column the card is in")
        void countsWithoutMoving() {
            Task task = task(0, 480);

            assertThat(task.countReopen()).isEqualTo((short) 1);
            assertThat(task.countReopen()).isEqualTo((short) 2);
            assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
        }

        @Test
        @DisplayName("saturates rather than wrapping into a negative count")
        void saturates() {
            Task task = Task.builder().reopenedCount(Short.MAX_VALUE).build();

            assertThat(task.countReopen()).isEqualTo(Short.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("charging the sprint")
    class Charging {

        @Test
        @DisplayName("hits over-budget as well as collaboration, under separate keys")
        void chargesBothRisks() {
            reporter.reopened(task(1, 480));

            assertThat(ledger.charged(RiskType.COMMUNICATION_COLLABORATION)).isPositive();
            assertThat(ledger.charged(RiskType.OVER_BUDGET)).isPositive();
            // Separate source keys, or the second revise would overwrite the first and
            // the sprint would carry only whichever risk happened to be written last.
            assertThat(ledger.keyFor(RiskType.COMMUNICATION_COLLABORATION))
                    .isNotEqualTo(ledger.keyFor(RiskType.OVER_BUDGET));
        }

        @Test
        @DisplayName("over-budget grows with the count — the estimate is already spent")
        void overBudgetGrowsWithTheCount() {
            BigDecimal first = chargeFor(1, 480, RiskType.OVER_BUDGET);
            BigDecimal fourth = chargeFor(4, 480, RiskType.OVER_BUDGET);

            assertThat(fourth).isGreaterThan(first);
        }

        @Test
        @DisplayName("a bigger task costs more than a smaller one for the same count")
        void weightedBySize() {
            BigDecimal small = chargeFor(2, 60, RiskType.OVER_BUDGET);
            BigDecimal large = chargeFor(2, 960, RiskType.OVER_BUDGET);

            assertThat(large).isGreaterThan(small);
        }

        @Test
        @DisplayName("neither charge may run away on one card")
        void capped() {
            reporter.reopened(task(50, 960));

            assertThat(ledger.charged(RiskType.COMMUNICATION_COLLABORATION))
                    .isLessThanOrEqualTo(BigDecimal.valueOf(12));
            assertThat(ledger.charged(RiskType.OVER_BUDGET))
                    .isLessThanOrEqualTo(BigDecimal.valueOf(14));
        }

        @Test
        @DisplayName("a card nobody has reopened is not charged at all")
        void silentAtZero() {
            reporter.reopened(task(0, 480));

            assertThat(ledger.entries).isEmpty();
        }
    }

    /**
     * The bug this pins: the credit for finishing inside the estimate used to be appended
     * on every completion, so a card cycling Done → review → Done banked -3 a lap. That
     * exactly cancelled the rework it was accruing, and the over-budget score sat still
     * through the one pattern it is meant to catch.
     */
    @Nested
    @DisplayName("bouncing in and out of Done")
    class Bouncing {

        /** One lap: reopen the card, then finish it again. */
        private void lap(Task task) {
            task.transitionTo(TaskStatus.IN_REVIEW);
            reporter.statusChanged(task, TaskStatus.DONE);
            task.transitionTo(TaskStatus.DONE);
            reporter.statusChanged(task, TaskStatus.IN_REVIEW);
        }

        @Test
        @DisplayName("costs more each lap rather than settling")
        void growsWithEachLap() {
            Task task = Task.builder()
                    .sprintId(SPRINT)
                    .itemKey("SIQ-2")
                    .status(TaskStatus.IN_PROGRESS)
                    .estimateMinutes(480)
                    .build();
            task.setId(UUID.randomUUID());

            task.transitionTo(TaskStatus.DONE);
            reporter.statusChanged(task, TaskStatus.IN_PROGRESS);
            BigDecimal onTime = ledger.charged(RiskType.OVER_BUDGET);

            lap(task);
            BigDecimal afterFirst = ledger.charged(RiskType.OVER_BUDGET);
            lap(task);
            BigDecimal afterSecond = ledger.charged(RiskType.OVER_BUDGET);

            // Finishing inside the estimate earns the credit exactly once...
            assertThat(onTime).isEqualByComparingTo(BigDecimal.valueOf(-3));
            // ...and every lap after it costs strictly more than the one before.
            assertThat(afterFirst).isGreaterThan(onTime);
            assertThat(afterSecond).isGreaterThan(afterFirst);
        }

        @Test
        @DisplayName("a reopened card is not still credited for having finished on time")
        void creditWithdrawnOnReopen() {
            Task task = Task.builder()
                    .sprintId(SPRINT)
                    .itemKey("SIQ-3")
                    .status(TaskStatus.IN_PROGRESS)
                    .estimateMinutes(480)
                    .build();
            task.setId(UUID.randomUUID());

            task.transitionTo(TaskStatus.DONE);
            reporter.statusChanged(task, TaskStatus.IN_PROGRESS);
            task.transitionTo(TaskStatus.IN_REVIEW);
            reporter.statusChanged(task, TaskStatus.DONE);

            assertThat(ledger.keys()).doesNotContain("TASK_ON_TIME:" + task.getId());
        }
    }
}
