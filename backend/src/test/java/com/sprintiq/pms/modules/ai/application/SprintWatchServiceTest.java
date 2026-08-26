package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.application.port.CollaborationFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.CollaborationFeatureSource.WatchedTask;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The between-assessment watch.
 *
 * <p>Most of these test <em>withdrawal</em>, not application. Applying a penalty is the
 * easy half and the half that gets tested; the half that decides whether anyone trusts
 * the number is whether it comes back off when the team fixes the thing. A score that
 * only ratchets upward is one people learn to ignore before the sprint is out.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint watch")
class SprintWatchServiceTest {

    private static final UUID SPRINT = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();

    @Mock private com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource sprints;
    @Mock private CollaborationFeatureSource collaboration;
    @Mock private SprintRiskAdjustmentService adjustments;
    @Mock private com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource stories;

    private SprintWatchService service;

    @BeforeEach
    void setUp() {
        service = new SprintWatchService(sprints, collaboration, adjustments, stories);
    }

    /** An 8-hour task, so half-time is 4 hours in and the deadline 8. */
    private static WatchedTask task(String status, Instant createdAt,
                                    long comments, Instant lastComment) {
        return new WatchedTask(TASK, "Wire up the export", status, 480,
                createdAt, comments, lastComment);
    }

    private void givenTask(WatchedTask task) {
        when(collaboration.watchedTasks(SPRINT)).thenReturn(List.of(task));
        // The story-budget rule runs on every sweep; no breaches unless a test says so.
        when(stories.budgetBreachesForSprint(SPRINT)).thenReturn(List.of());
        when(adjustments.ledgerFor(SPRINT)).thenReturn(
                new SprintRiskAdjustmentService.Ledger(List.of()));
    }

    @Test
    @DisplayName("charges a task nobody has spoken about past half its estimate")
    void silentTaskIsCharged() {
        givenTask(task("IN_PROGRESS", Instant.now().minus(Duration.ofHours(6)), 0, null));

        service.watch(SPRINT);

        verify(adjustments).recordOnce(eq(SPRINT), eq(RiskType.COMMUNICATION_COLLABORATION),
                eq(SprintWatchService.KEY_TASK_SILENT + TASK), any(), any());
    }

    @Test
    @DisplayName("withdraws the silence penalty as soon as somebody comments")
    void silenceIsForgivenOnFirstComment() {
        givenTask(task("IN_PROGRESS", Instant.now().minus(Duration.ofHours(6)),
                1, Instant.now()));

        service.watch(SPRINT);

        verify(adjustments).clear(SPRINT, SprintWatchService.KEY_TASK_SILENT + TASK);
        verify(adjustments, never()).recordOnce(any(), eq(RiskType.COMMUNICATION_COLLABORATION),
                eq(SprintWatchService.KEY_TASK_SILENT + TASK), any(), any());
    }

    @Test
    @DisplayName("does not charge a task that is still within its estimate")
    void quietButYoungTaskIsLeftAlone() {
        givenTask(task("IN_PROGRESS", Instant.now().minus(Duration.ofHours(1)), 0, null));

        service.watch(SPRINT);

        verify(adjustments, never()).recordOnce(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("charges a question left hanging over a day")
    void unansweredCommentIsCharged() {
        givenTask(task("IN_PROGRESS", Instant.now().minus(Duration.ofHours(30)),
                2, Instant.now().minus(Duration.ofHours(26))));

        service.watch(SPRINT);

        verify(adjustments).recordOnce(eq(SPRINT), eq(RiskType.COMMUNICATION_COLLABORATION),
                eq(SprintWatchService.KEY_COMMENT_UNANSWERED + TASK), any(), any());
    }

    @Test
    @DisplayName("withdraws it the moment somebody replies")
    void answeringWithdrawsThePenalty() {
        givenTask(task("IN_PROGRESS", Instant.now().minus(Duration.ofHours(30)),
                3, Instant.now().minus(Duration.ofMinutes(5))));

        service.watch(SPRINT);

        verify(adjustments).clear(SPRINT, SprintWatchService.KEY_COMMENT_UNANSWERED + TASK);
    }

    @Test
    @DisplayName("charges both models when a task has not been started at half-time")
    void notStartedHitsBudgetAndCollaboration() {
        givenTask(task("TODO", Instant.now().minus(Duration.ofHours(6)), 1, Instant.now()));

        service.watch(SPRINT);

        verify(adjustments).recordOnce(eq(SPRINT), eq(RiskType.COMMUNICATION_COLLABORATION),
                eq(SprintWatchService.KEY_NOT_STARTED_HALF + TASK), any(), any());
        verify(adjustments).recordOnce(eq(SPRINT), eq(RiskType.OVER_BUDGET),
                eq(SprintWatchService.KEY_NOT_STARTED_HALF + TASK + ":budget"), any(), any());
    }

    @Test
    @DisplayName("withdraws BOTH not-started penalties once the task is picked up")
    void startingTheTaskWithdrawsIt() {
        // The regression. This rule charges two models for one fact, so it has to
        // withdraw two. Clearing only the collaboration key left the over-budget half
        // standing forever: starting the task visibly removed half the penalty and
        // silently kept the rest, which is the ratchet this design exists to avoid.
        givenTask(task("IN_PROGRESS", Instant.now().minus(Duration.ofHours(6)),
                1, Instant.now()));

        service.watch(SPRINT);

        verify(adjustments).clear(SPRINT, SprintWatchService.KEY_NOT_STARTED_HALF + TASK);
        verify(adjustments).clear(SPRINT,
                SprintWatchService.KEY_NOT_STARTED_HALF + TASK + ":budget");
    }

    @Test
    @DisplayName("charges over-budget for a task past its estimate")
    void overdueTaskIsCharged() {
        givenTask(task("IN_PROGRESS", Instant.now().minus(Duration.ofHours(12)),
                4, Instant.now()));

        service.watch(SPRINT);

        verify(adjustments).recordOnce(eq(SPRINT), eq(RiskType.OVER_BUDGET),
                eq(SprintWatchService.KEY_NOT_DONE_OVERDUE + TASK), any(), any());
    }

    @Test
    @DisplayName("finishing a task clears every penalty against it")
    void finishingClearsEverything() {
        // Late, silent and long overdue — but done. Nothing should still be charged.
        givenTask(task("DONE", Instant.now().minus(Duration.ofHours(40)), 0, null));

        service.watch(SPRINT);

        verify(adjustments).clear(SPRINT, SprintWatchService.KEY_TASK_SILENT + TASK);
        verify(adjustments).clear(SPRINT, SprintWatchService.KEY_NOT_DONE_OVERDUE + TASK);
        verify(adjustments, never()).recordOnce(any(), any(), any(), any(), any());
    }

    @Nested
    @DisplayName("story budgets")
    class StoryBudgets {

        private static final UUID STORY = UUID.randomUUID();

        private void givenBreach(long overrunMinutes, long budgetMinutes) {
            when(collaboration.watchedTasks(SPRINT)).thenReturn(List.of(
                    task("IN_PROGRESS", Instant.now(), 1, Instant.now())));
            when(stories.budgetBreachesForSprint(SPRINT)).thenReturn(List.of(
                    new com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource
                            .StoryBudgetBreach(STORY, "PAY-7", overrunMinutes, budgetMinutes)));
            when(adjustments.ledgerFor(SPRINT)).thenReturn(
                    new SprintRiskAdjustmentService.Ledger(List.of()));
        }

        @Test
        @DisplayName("charges over-budget when tasks outgrow the story's points")
        void breachIsCharged() {
            // The worked example: a 5-point story (300m) broken into 6 hours of tasks.
            givenBreach(60, 300);

            service.watch(SPRINT);

            verify(adjustments).revise(eq(SPRINT), eq(RiskType.OVER_BUDGET),
                    eq(SprintWatchService.KEY_STORY_OVER_BUDGET + STORY), any(), any());
        }

        @Test
        @DisplayName("charges proportionally — a bigger miss costs more")
        void deltaScalesWithTheOverrun() {
            ArgumentCaptor<BigDecimal> delta = ArgumentCaptor.forClass(BigDecimal.class);

            givenBreach(60, 300);              // 1 hour over
            service.watch(SPRINT);
            verify(adjustments).revise(any(), any(), any(), delta.capture(), any());
            BigDecimal small = delta.getValue();

            org.mockito.Mockito.reset(adjustments);
            givenBreach(480, 300);             // a full day over
            service.watch(SPRINT);
            verify(adjustments).revise(any(), any(), any(), delta.capture(), any());

            assertThat(delta.getValue()).isGreaterThan(small);
        }

        @Test
        @DisplayName("caps a single mis-sized story so it cannot swamp the ledger")
        void deltaIsCapped() {
            givenBreach(60 * 40, 300);   // forty hours over

            service.watch(SPRINT);

            ArgumentCaptor<BigDecimal> delta = ArgumentCaptor.forClass(BigDecimal.class);
            verify(adjustments).revise(any(), any(), any(), delta.capture(), any());
            assertThat(delta.getValue()).isLessThanOrEqualTo(BigDecimal.valueOf(12));
        }

        @Test
        @DisplayName("revises the same entry rather than stacking a second")
        void usesReviseNotRecord() {
            // A story going further over should deepen one reading, not append. Appending
            // would grow the ledger with edits rather than with evidence.
            givenBreach(120, 300);

            service.watch(SPRINT);

            verify(adjustments, never()).recordOnce(any(), eq(RiskType.OVER_BUDGET),
                    eq(SprintWatchService.KEY_STORY_OVER_BUDGET + STORY), any(), any());
        }

        @Test
        @DisplayName("withdraws the charge once the story comes back inside its budget")
        void breachClearsWhenCorrected() {
            // Previously charged, no longer breaching — re-pointing the story or
            // re-estimating a task has to visibly remove the penalty.
            when(collaboration.watchedTasks(SPRINT)).thenReturn(List.of(
                    task("IN_PROGRESS", Instant.now(), 1, Instant.now())));
            when(stories.budgetBreachesForSprint(SPRINT)).thenReturn(List.of());
            when(adjustments.ledgerFor(SPRINT)).thenReturn(
                    new SprintRiskAdjustmentService.Ledger(List.of(
                            com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustment.builder()
                                    .sprintId(SPRINT)
                                    .riskType(RiskType.OVER_BUDGET)
                                    .delta(BigDecimal.valueOf(4))
                                    .sourceKey(SprintWatchService.KEY_STORY_OVER_BUDGET + STORY)
                                    .reason("PAY-7 over budget")
                                    .build())));

            service.watch(SPRINT);

            verify(adjustments).clear(SPRINT,
                    SprintWatchService.KEY_STORY_OVER_BUDGET + STORY);
        }

        @Test
        @DisplayName("names the story and the size of the miss, in hours a person reads")
        void reasonIsReadable() {
            givenBreach(90, 300);

            service.watch(SPRINT);

            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(adjustments).revise(any(), any(), any(), any(), reason.capture());
            assertThat(reason.getValue()).contains("PAY-7").contains("1h 30m").contains("5h");
        }
    }

    @Test
    @DisplayName("skips a task nobody estimated rather than inventing a deadline")
    void unestimatedTaskHasNoClock() {
        WatchedTask unestimated = new WatchedTask(TASK, "Spike", "TODO", null,
                Instant.now().minus(Duration.ofDays(9)), 0, null);
        givenTask(unestimated);

        service.watch(SPRINT);

        verify(adjustments, never()).recordOnce(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the penalties stay small — these are nudges, not verdicts")
    void deltasAreSmall() {
        givenTask(task("TODO", Instant.now().minus(Duration.ofHours(12)), 0, null));

        service.watch(SPRINT);

        ArgumentCaptor<BigDecimal> deltas = ArgumentCaptor.forClass(BigDecimal.class);
        verify(adjustments, org.mockito.Mockito.atLeastOnce())
                .recordOnce(any(), any(), any(), deltas.capture(), any());
        assertThat(deltas.getAllValues())
                .allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(BigDecimal.valueOf(5)));
    }
}
