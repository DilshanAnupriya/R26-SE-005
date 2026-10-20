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
import java.time.Duration;
import java.time.Instant;
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
@DisplayName("Board events as risk")
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
        public void reviseStanding(UUID sprintId, RiskType riskType, String sourceKey,
                                   BigDecimal delta, String reason) {
            revise(sprintId, riskType, sourceKey, delta, reason);
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
     * A task in a sprint with nobody assigned raises collaboration risk by its size;
     * assigning it gives back the share matching the estimate still left, and extending
     * the estimate gives back the rest of the wait.
     */
    @Nested
    @DisplayName("ownership")
    class Ownership {

        private Task owned(int estimateThen, Integer estimateNow, Long waitedSeconds) {
            Task task = Task.builder()
                    .sprintId(SPRINT)
                    .itemKey("SIQ-6")
                    .status(TaskStatus.TODO)
                    .assigneeId(UUID.randomUUID())
                    .estimateMinutes(estimateNow)
                    .estimateAtAssignMinutes(estimateThen)
                    .unassignedElapsedSeconds(waitedSeconds)
                    .build();
            task.setId(UUID.randomUUID());
            return task;
        }

        private BigDecimal charge(Task task) {
            return ledger.entries.stream()
                    .filter(entry -> ("TASK_UNASSIGNED:" + task.getId()).equals(entry.sourceKey()))
                    .map(Entry::delta)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("nobody assigned raises collaboration by the task's weight")
        void unassignedIsCharged() {
            Task small = task(0, 60);
            Task day = task(0, 480);
            reporter.ownershipChanged(small);
            reporter.ownershipChanged(day);

            assertThat(charge(small)).isEqualByComparingTo(BigDecimal.valueOf(3));
            assertThat(charge(day)).isEqualByComparingTo(BigDecimal.valueOf(6));
            assertThat(ledger.entries).allMatch(e -> e.riskType() == RiskType.COMMUNICATION_COLLABORATION);
        }

        @Test
        @DisplayName("assigned straight away, nothing is left")
        void assignedAtOnce() {
            Task task = owned(60, 60, 0L);
            reporter.ownershipChanged(task);

            assertThat(charge(task)).isZero();
        }

        @Test
        @DisplayName("assigned a third of the way in, a third of the charge stays")
        void partShareStays() {
            Task task = owned(60, 60, 20L * 60);
            reporter.ownershipChanged(task);

            assertThat(charge(task)).isEqualByComparingTo(BigDecimal.valueOf(1));
        }

        @Test
        @DisplayName("extending the estimate by the wait gives the rest back")
        void extensionClears() {
            Task halfBack = owned(60, 70, 20L * 60);
            reporter.ownershipChanged(halfBack);
            assertThat(charge(halfBack)).isEqualByComparingTo(BigDecimal.valueOf(0.5));

            Task allBack = owned(60, 80, 20L * 60);
            reporter.ownershipChanged(allBack);
            assertThat(charge(allBack)).isZero();
        }

        @Test
        @DisplayName("assigned after the estimate ran out, the whole charge stays")
        void lateKeepsAll() {
            Task task = owned(60, 60, 2L * 3600);
            reporter.ownershipChanged(task);

            assertThat(charge(task)).isEqualByComparingTo(BigDecimal.valueOf(3));
        }

        @Test
        @DisplayName("a task finished with nobody on it is no longer charged")
        void finishedUnownedClears() {
            Task task = task(0, 60);
            reporter.ownershipChanged(task);
            task.transitionTo(TaskStatus.DONE);
            reporter.statusChanged(task, TaskStatus.IN_REVIEW);

            assertThat(charge(task)).isZero();
        }

        @Test
        @DisplayName("the first assignment is stamped once, from the task's own clock")
        void stampedOnce() {
            Task task = Task.builder().status(TaskStatus.TODO).estimateMinutes(60)
                    .elapsedSeconds(600).build();
            task.setAssigneeId(UUID.randomUUID());
            task.stampFirstAssignment(java.time.Instant.now());
            task.setElapsedSeconds(1800);
            task.setAssigneeId(UUID.randomUUID());
            task.stampFirstAssignment(java.time.Instant.now());

            assertThat(task.getUnassignedElapsedSeconds()).isEqualTo(600L);
            assertThat(task.getEstimateAtAssignMinutes()).isEqualTo(60);
        }
    }

    /** Talking on the task lowers collaboration risk, by the task's size. */
    @Nested
    @DisplayName("comments and replies")
    class Discussion {

        private BigDecimal at(String keyPrefix, Task task) {
            return ledger.entries.stream()
                    .filter(entry -> (keyPrefix + task.getId()).equals(entry.sourceKey()))
                    .map(Entry::delta)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a comment lowers collaboration by the task's weight")
        void commentCredits() {
            Task small = task(0, 60);
            Task day = task(0, 480);
            reporter.discussionChanged(small, 1, 0);
            reporter.discussionChanged(day, 1, 0);

            assertThat(at("TASK_COMMENTED:", small)).isEqualByComparingTo(BigDecimal.valueOf(-1));
            assertThat(at("TASK_COMMENTED:", day)).isEqualByComparingTo(BigDecimal.valueOf(-2));
            assertThat(ledger.entries).allMatch(e -> e.riskType() == RiskType.COMMUNICATION_COLLABORATION);
        }

        @Test
        @DisplayName("a reply lowers it by more than a comment")
        void replyCredits() {
            Task day = task(0, 480);
            reporter.discussionChanged(day, 1, 1);

            assertThat(at("TASK_REPLIED:", day)).isEqualByComparingTo(BigDecimal.valueOf(-3));
            assertThat(ledger.charged(RiskType.COMMUNICATION_COLLABORATION))
                    .isEqualByComparingTo(BigDecimal.valueOf(-5));
        }

        @Test
        @DisplayName("only the first three of each count, so spam cannot buy the risk down")
        void capped() {
            Task day = task(0, 480);
            reporter.discussionChanged(day, 40, 40);

            assertThat(at("TASK_COMMENTED:", day)).isEqualByComparingTo(BigDecimal.valueOf(-6));
            assertThat(at("TASK_REPLIED:", day)).isEqualByComparingTo(BigDecimal.valueOf(-9));
        }

        @Test
        @DisplayName("deleting the discussion gives the credit back")
        void deletionWithdraws() {
            Task day = task(0, 480);
            reporter.discussionChanged(day, 2, 1);
            reporter.discussionChanged(day, 0, 0);

            assertThat(ledger.entries).isEmpty();
        }

        @Test
        @DisplayName("the first comment ends the sweep's silence charge at once")
        void endsTheSilenceCharge() {
            Task day = task(0, 480);
            ledger.revise(SPRINT, RiskType.COMMUNICATION_COLLABORATION,
                    "TASK_SILENT:" + day.getId(), BigDecimal.valueOf(6), "silent");

            reporter.discussionChanged(day, 1, 0);

            assertThat(ledger.keys()).doesNotContain("TASK_SILENT:" + day.getId());
        }
    }

    /**
     * The other direction: a task moved to In Progress or Done while its estimate still
     * has time on it lowers over-budget, by its size.
     */
    @Nested
    @DisplayName("moving a card on time")
    class OnTime {

        private Task fresh(int estimateMinutes, long elapsedSeconds) {
            Task task = Task.builder()
                    .sprintId(SPRINT)
                    .itemKey("SIQ-4")
                    .status(TaskStatus.TODO)
                    .estimateMinutes(estimateMinutes)
                    .elapsedSeconds(elapsedSeconds)
                    .build();
            task.setId(UUID.randomUUID());
            return task;
        }

        private BigDecimal creditFor(Task task, String keyPrefix) {
            return ledger.entries.stream()
                    .filter(entry -> (keyPrefix + task.getId()).equals(entry.sourceKey()))
                    .map(Entry::delta)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("starting within the estimate lowers over-budget by the task's weight")
        void startedOnTime() {
            Task small = fresh(60, 600);   // ten minutes into an hour
            small.transitionTo(TaskStatus.IN_PROGRESS);
            reporter.statusChanged(small, TaskStatus.TODO);

            Task day = fresh(480, 600);
            day.transitionTo(TaskStatus.IN_PROGRESS);
            reporter.statusChanged(day, TaskStatus.TODO);

            assertThat(creditFor(small, "TASK_STARTED_ON_TIME:")).isEqualByComparingTo(BigDecimal.valueOf(-2));
            assertThat(creditFor(day, "TASK_STARTED_ON_TIME:")).isEqualByComparingTo(BigDecimal.valueOf(-4));
        }

        @Test
        @DisplayName("finishing within the estimate lowers it by the task's weight")
        void finishedOnTime() {
            Task small = fresh(60, 600);
            small.transitionTo(TaskStatus.DONE);
            reporter.statusChanged(small, TaskStatus.IN_PROGRESS);

            assertThat(creditFor(small, "TASK_ON_TIME:")).isEqualByComparingTo(BigDecimal.valueOf(-3));
        }

        @Test
        @DisplayName("past the estimate on the board's clock, neither move earns a credit")
        void lateEarnsNothing() {
            Task late = fresh(60, 2 * 3600);   // two hours into a one-hour task
            late.transitionTo(TaskStatus.IN_PROGRESS);
            reporter.statusChanged(late, TaskStatus.TODO);
            late.transitionTo(TaskStatus.DONE);
            reporter.statusChanged(late, TaskStatus.IN_PROGRESS);

            assertThat(ledger.keys()).noneMatch(key -> key.contains("ON_TIME"));
        }

        @Test
        @DisplayName("going back from review to In Progress is not a fresh start")
        void reviewBounceIsNotAStart() {
            Task task = fresh(60, 600);
            task.transitionTo(TaskStatus.IN_REVIEW);
            task.transitionTo(TaskStatus.IN_PROGRESS);
            reporter.statusChanged(task, TaskStatus.IN_REVIEW);

            assertThat(creditFor(task, "TASK_STARTED_ON_TIME:")).isZero();
        }

        @Test
        @DisplayName("an unestimated task has no estimate to be inside of")
        void unestimatedEarnsNothing() {
            Task task = Task.builder().sprintId(SPRINT).itemKey("SIQ-5")
                    .status(TaskStatus.TODO).build();
            task.setId(UUID.randomUUID());
            task.transitionTo(TaskStatus.IN_PROGRESS);
            reporter.statusChanged(task, TaskStatus.TODO);

            assertThat(ledger.entries).isEmpty();
        }
    }

    /**
     * The rule as the team states it: a task moved to Done is finished, and the same task
     * moved anywhere else afterwards is a reopen that costs over-budget by its size.
     */
    @Nested
    @DisplayName("moving a finished card out of Done")
    class OutOfDone {

        private BigDecimal reworkAfterMovingTo(TaskStatus target, int estimateMinutes) {
            Task task = task(0, estimateMinutes);
            task.transitionTo(TaskStatus.DONE);
            reporter.statusChanged(task, TaskStatus.IN_REVIEW);

            task.transitionTo(target);
            reporter.statusChanged(task, TaskStatus.DONE);
            return ledger.entries.stream()
                    .filter(entry -> ("TASK_REOPEN_BUDGET:" + task.getId()).equals(entry.sourceKey()))
                    .map(Entry::delta)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(value = TaskStatus.class,
                names = {"TODO", "IN_PROGRESS", "IN_REVIEW", "BLOCKED"})
        @DisplayName("to any open column is a reopen charged to over-budget")
        void everyOpenColumnIsAReopen(TaskStatus target) {
            // A one-hour task weighs 0.5, so its first reopen is half the six-point base.
            assertThat(reworkAfterMovingTo(target, 60)).isEqualByComparingTo(BigDecimal.valueOf(3));
        }

        @Test
        @DisplayName("a bigger task costs more")
        void weightedBySize() {
            assertThat(reworkAfterMovingTo(TaskStatus.TODO, 480))
                    .isEqualByComparingTo(BigDecimal.valueOf(6));
        }

        @Test
        @DisplayName("moving between open columns is not a reopen")
        void ordinaryMovesAreFree() {
            Task task = task(0, 60);
            task.transitionTo(TaskStatus.IN_PROGRESS);
            reporter.statusChanged(task, TaskStatus.IN_REVIEW);

            assertThat(ledger.keys()).noneMatch(key -> key.startsWith("TASK_REOPEN_BUDGET:"));
        }

        @Test
        @DisplayName("a story card taken out of Done is charged the same way")
        void storyCards() {
            UUID story = UUID.randomUUID();
            reporter.storyReopened(SPRINT, story, "PAY-5", 60, 1);

            assertThat(ledger.keyFor(RiskType.OVER_BUDGET)).isEqualTo("TASK_REOPEN_BUDGET:" + story);
            assertThat(ledger.charged(RiskType.OVER_BUDGET)).isEqualByComparingTo(BigDecimal.valueOf(3));
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
            // A one-day task, so the full six-point credit.
            assertThat(onTime).isEqualByComparingTo(BigDecimal.valueOf(-6));
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

    /**
     * A question nobody answered, charged against half the task's own estimate.
     *
     * <p>Relative rather than a fixed number of hours: two hours of silence on a one-hour
     * task has already sunk it, and on a three-day task is a normal morning.</p>
     */
    @Nested
    @DisplayName("an unanswered comment")
    class UnansweredComments {

        private Task estimated(int minutes) {
            Task task = Task.builder()
                    .sprintId(SPRINT)
                    .itemKey("SIQ-4")
                    .status(TaskStatus.IN_PROGRESS)
                    .estimateMinutes(minutes)
                    .build();
            task.setId(UUID.randomUUID());
            return task;
        }

        private BigDecimal chargeAfter(int estimateMinutes, int waitedMinutes) {
            Recorder fresh = new Recorder();
            new TaskRiskReporter(fresh).commentUnanswered(estimated(estimateMinutes),
                    Instant.now().minus(Duration.ofMinutes(waitedMinutes)));
            return fresh.charged(RiskType.COMMUNICATION_COLLABORATION);
        }

        @Test
        @DisplayName("costs nothing before half the estimate has passed")
        void quietInsideThePatience() {
            // A one-hour task tolerates thirty minutes.
            assertThat(chargeAfter(60, 20)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("is charged once the wait passes half the estimate")
        void chargedPastThePatience() {
            assertThat(chargeAfter(60, 45)).isPositive();
        }

        @Test
        @DisplayName("gets louder the longer it is ignored")
        void growsWithTheWait() {
            BigDecimal justOver = chargeAfter(60, 35);
            BigDecimal allDay = chargeAfter(60, 300);

            assertThat(allDay).isGreaterThan(justOver);
        }

        @Test
        @DisplayName("measures against the task's own clock, not a fixed hour")
        void scaledToTheEstimate() {
            // Ninety minutes of silence: past half of a one-hour task, nowhere near half
            // of a three-day one.
            assertThat(chargeAfter(60, 90)).isPositive();
            assertThat(chargeAfter(1440, 90)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("is silent on a task nobody estimated")
        void silentWithoutAnEstimate() {
            Recorder fresh = new Recorder();
            Task unestimated = Task.builder().sprintId(SPRINT).itemKey("SIQ-5")
                    .status(TaskStatus.IN_PROGRESS).build();
            unestimated.setId(UUID.randomUUID());

            new TaskRiskReporter(fresh)
                    .commentUnanswered(unestimated, Instant.now().minus(Duration.ofDays(3)));

            // There is no half of an unstated duration. Guessing one would charge every
            // unestimated card the moment somebody commented on it.
            assertThat(fresh.entries).isEmpty();
        }

        @Test
        @DisplayName("is withdrawn once somebody answers")
        void withdrawnWhenAnswered() {
            Task task = estimated(60);
            reporter.commentUnanswered(task, Instant.now().minus(Duration.ofMinutes(90)));
            assertThat(ledger.charged(RiskType.COMMUNICATION_COLLABORATION)).isPositive();

            reporter.commentAnswered(task);

            // A charge that can only ever go up stops describing the sprint and starts
            // describing its history.
            assertThat(ledger.keys()).doesNotContain("TASK_UNANSWERED:" + task.getId());
        }
    }
}
