package com.sprintiq.pms.modules.ai;

import com.sprintiq.pms.modules.ai.application.AiModelClient;
import com.sprintiq.pms.modules.ai.application.FeatureVector;
import com.sprintiq.pms.modules.ai.application.MockAiModelClient;
import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.InferenceMode;
import com.sprintiq.pms.modules.ai.domain.PredictionDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mock client is what makes the AI features demonstrable before any model service
 * exists, which means it has to behave like a model rather than like a stub. These tests
 * pin the properties the product depends on: the same features always produce the same
 * answer, more scope produces a bigger number, thinner evidence produces lower
 * confidence, and every usable result carries the drivers the UI is going to render.
 */
@DisplayName("Mock AI client")
class MockAiModelClientTest {

    private final MockAiModelClient client = new MockAiModelClient();

    // ------------------------------------------------------------------ fixtures

    private AiModelClient.InferenceResult run(AiModelCode code, FeatureVector features) {
        return client.infer(new AiModelClient.InferenceRequest(code, "1.0.0",
                InferenceMode.ZERO_SHOT, features, Duration.ofSeconds(4)));
    }

    private FeatureVector story(int criteria, int words, int dependencies, boolean described) {
        return FeatureVector.create()
                .put("story_type", "STORY")
                .putNumber("acceptance_criteria_count", criteria)
                .putNumber("word_count", words)
                .putNumber("dependency_count", dependencies)
                .putNumber("task_count", 0)
                .put("has_description", described)
                .putNumber("project_median_points", BigDecimal.valueOf(5))
                .putNumber("comparable_median_points", BigDecimal.valueOf(5))
                .putNumber("comparable_count", 12);
    }

    private FeatureVector sprint(double commitmentRatio, double volatility, double elapsed) {
        return FeatureVector.create()
                .putNumber("commitment_ratio", commitmentRatio)
                .putNumber("scope_volatility", volatility)
                .putNumber("elapsed_fraction", elapsed)
                .putNumber("effort_burn_ratio", elapsed)
                .putNumber("story_count", 12)
                .putNumber("blocked_count", 0)
                .putNumber("effort_minutes", 4800)
                .putNumber("overtime_minutes", 0)
                .putNumber("unestimated_count", 0)
                .putNumber("reopened_count", 0)
                .putNumber("requirement_churn_rate", 0)
                .putNumber("history_depth", 4)
                .put("has_sprint_goal", true);
    }

    // --------------------------------------------------------------- determinism

    @Test
    @DisplayName("identical features always produce an identical answer")
    void isDeterministic() {
        AiModelClient.InferenceResult first = run(AiModelCode.M1_STORY_POINTS, story(3, 120, 1, true));
        AiModelClient.InferenceResult second = run(AiModelCode.M1_STORY_POINTS, story(3, 120, 1, true));

        assertThat(first.value()).isEqualByComparingTo(second.value());
        assertThat(first.confidence()).isEqualByComparingTo(second.confidence());
        assertThat(first.explanation()).isEqualTo(second.explanation());
    }

    // ------------------------------------------------------------------ M1 · size

    @Test
    @DisplayName("more acceptance criteria and more dependencies size a story larger")
    void scopeSignalsIncreaseTheEstimate() {
        BigDecimal small = run(AiModelCode.M1_STORY_POINTS, story(1, 40, 0, true)).value();
        BigDecimal large = run(AiModelCode.M1_STORY_POINTS, story(8, 400, 3, true)).value();

        assertThat(large).isGreaterThan(small);
    }

    @Test
    @DisplayName("estimates land on the Fibonacci scale a team actually uses")
    void estimatesSnapToTheEstimationScale() {
        List<BigDecimal> scale = List.of(BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(3),
                BigDecimal.valueOf(5), BigDecimal.valueOf(8), BigDecimal.valueOf(13),
                BigDecimal.valueOf(21));

        for (int criteria = 0; criteria <= 10; criteria++) {
            BigDecimal points = run(AiModelCode.M1_STORY_POINTS, story(criteria, 150, 0, true)).value();
            assertThat(scale).as("estimate for %d criteria", criteria)
                    .anyMatch(allowed -> allowed.compareTo(points) == 0);
        }
    }

    @Test
    @DisplayName("no comparable history means lower confidence and a wider interval")
    void thinEvidenceLowersConfidence() {
        FeatureVector unknown = story(0, 20, 0, false)
                .putNumber("comparable_count", 0)
                .putNumber("comparable_median_points", BigDecimal.ZERO)
                .putNumber("project_median_points", BigDecimal.ZERO);

        AiModelClient.InferenceResult thin = run(AiModelCode.M1_STORY_POINTS, unknown);
        AiModelClient.InferenceResult rich = run(AiModelCode.M1_STORY_POINTS, story(4, 200, 0, true));

        assertThat(thin.confidence()).isLessThan(rich.confidence());
        assertThat(thin.upperBound()).isGreaterThanOrEqualTo(thin.lowerBound());
        assertThat(thin.explanation()).contains("No comparable stories");
    }

    @Test
    @DisplayName("an unspecified story is sized up, not down")
    void missingDescriptionIsTreatedAsUnknownRatherThanSmall() {
        BigDecimal specified = run(AiModelCode.M1_STORY_POINTS, story(2, 100, 0, true)).value();
        BigDecimal unspecified = run(AiModelCode.M1_STORY_POINTS, story(2, 100, 0, false)).value();

        assertThat(unspecified).isGreaterThanOrEqualTo(specified);
    }

    // -------------------------------------------------------------- M1 · velocity

    @Test
    @DisplayName("velocity weights recent sprints more heavily than old ones")
    void velocityFavoursRecentSprints() {
        FeatureVector improving = FeatureVector.create()
                .put("historical_velocities", List.of(10, 15, 25))
                .putNumber("team_size", 5).putNumber("duration_days", 10);
        FeatureVector declining = FeatureVector.create()
                .put("historical_velocities", List.of(25, 15, 10))
                .putNumber("team_size", 5).putNumber("duration_days", 10);

        BigDecimal up = run(AiModelCode.M1_VELOCITY, improving).value();
        BigDecimal down = run(AiModelCode.M1_VELOCITY, declining).value();

        assertThat(up).as("same mean, opposite trajectories").isGreaterThan(down);
    }

    @Test
    @DisplayName("planned absence reduces the predicted velocity")
    void absenceReducesVelocity() {
        FeatureVector base = FeatureVector.create()
                .put("historical_velocities", List.of(20, 20, 20))
                .putNumber("team_size", 5).putNumber("duration_days", 10);
        FeatureVector depleted = FeatureVector.create()
                .put("historical_velocities", List.of(20, 20, 20))
                .putNumber("team_size", 5).putNumber("duration_days", 10)
                .putNumber("absence_days", BigDecimal.valueOf(10));

        assertThat(run(AiModelCode.M1_VELOCITY, depleted).value())
                .isLessThan(run(AiModelCode.M1_VELOCITY, base).value());
    }

    // ------------------------------------------------------------------ M2 · risk

    @Test
    @DisplayName("over-commitment and scope churn raise over-budget risk")
    void riskRespondsToStressors() {
        BigDecimal calm = run(AiModelCode.M2_OVER_BUDGET, sprint(0.9, 0.0, 0.4)).value();
        BigDecimal stressed = run(AiModelCode.M2_OVER_BUDGET, sprint(1.6, 0.45, 0.8)).value();

        assertThat(calm).isLessThan(BigDecimal.valueOf(0.5));
        assertThat(stressed).isGreaterThan(calm);
    }

    @Test
    @DisplayName("risk scores stay inside 0..1 even when every stressor is extreme")
    void riskIsBounded() {
        FeatureVector extreme = sprint(9.0, 5.0, 1.0)
                .putNumber("blocked_count", 12)
                .putNumber("overtime_minutes", 4800)
                .putNumber("reopened_count", 12)
                .putNumber("unestimated_count", 12);

        BigDecimal score = run(AiModelCode.M2_OVER_BUDGET, extreme).value();
        assertThat(score).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    @DisplayName("a sprint with no goal is flagged as more likely to churn")
    void missingSprintGoalRaisesRequirementChangeRisk() {
        BigDecimal withGoal = run(AiModelCode.M2_REQUIREMENT_CHANGE, sprint(1.0, 0.0, 0.3)).value();
        BigDecimal withoutGoal = run(AiModelCode.M2_REQUIREMENT_CHANGE,
                sprint(1.0, 0.0, 0.3).put("has_sprint_goal", false)).value();

        assertThat(withoutGoal).isGreaterThan(withGoal);
    }

    // ------------------------------------------------------------ M3 · allocation

    @Test
    @DisplayName("an overloaded candidate is penalised but still scored")
    void workloadCountsAgainstFitWithoutHidingTheCandidate() {
        FeatureVector available = FeatureVector.create()
                .putNumber("required_skill_count", 2).putNumber("skill_match_ratio", 1.0)
                .putNumber("consistency_score", 0.8).putNumber("current_load_pct", 40)
                .putNumber("availability_rate", 1.0).putNumber("avg_velocity", 8)
                .putNumber("tasks_completed", 40);
        FeatureVector swamped = FeatureVector.create()
                .putNumber("required_skill_count", 2).putNumber("skill_match_ratio", 1.0)
                .putNumber("consistency_score", 0.8).putNumber("current_load_pct", 150)
                .putNumber("availability_rate", 1.0).putNumber("avg_velocity", 8)
                .putNumber("tasks_completed", 40);

        AiModelClient.InferenceResult free = run(AiModelCode.M3_ALLOCATION, available);
        AiModelClient.InferenceResult busy = run(AiModelCode.M3_ALLOCATION, swamped);

        assertThat(busy.value()).isLessThan(free.value());
        assertThat(busy.value()).isGreaterThan(BigDecimal.ZERO);
        assertThat(busy.drivers()).extracting(PredictionDriver::feature).contains("current_load_pct");
    }

    // ------------------------------------------------------------------ M4 · cost

    @Test
    @DisplayName("the mid-sprint forecast is tighter than the initial one")
    void midSprintForecastNarrowsTheMargin() {
        FeatureVector features = FeatureVector.create()
                .putNumber("capacity_hours", BigDecimal.valueOf(400))
                .putNumber("blended_hourly_rate", BigDecimal.valueOf(70))
                .putNumber("effort_minutes", BigDecimal.valueOf(12000))
                .putNumber("elapsed_fraction", BigDecimal.valueOf(0.5))
                .putNumber("team_size", 5).putNumber("duration_days", 10);

        AiModelClient.InferenceResult initial = run(AiModelCode.M4_COST_INITIAL, features);
        AiModelClient.InferenceResult mid = run(AiModelCode.M4_COST_MID, features);

        BigDecimal initialSpread = initial.upperBound().subtract(initial.lowerBound())
                .divide(initial.value(), 4, java.math.RoundingMode.HALF_UP);
        BigDecimal midSpread = mid.upperBound().subtract(mid.lowerBound())
                .divide(mid.value(), 4, java.math.RoundingMode.HALF_UP);

        assertThat(midSpread).as("logged effort should buy precision").isLessThan(initialSpread);
    }

    @Test
    @DisplayName("every forecast carries bounds, never a bare figure")
    void costAlwaysReportsUncertainty() {
        AiModelClient.InferenceResult result = run(AiModelCode.M4_COST_INITIAL,
                FeatureVector.create()
                        .putNumber("capacity_hours", BigDecimal.valueOf(320))
                        .putNumber("blended_hourly_rate", BigDecimal.valueOf(60)));

        assertThat(result.lowerBound()).isNotNull();
        assertThat(result.upperBound()).isNotNull();
        assertThat(result.lowerBound()).isLessThan(result.value());
        assertThat(result.upperBound()).isGreaterThan(result.value());
    }

    // ---------------------------------------------------------------- explanation

    @Test
    @DisplayName("every model returns ranked drivers and a readable explanation")
    void everyModelExplainsItself() {
        for (AiModelCode code : AiModelCode.values()) {
            FeatureVector features = switch (code) {
                case M1_STORY_POINTS -> story(3, 150, 1, true);
                case M1_VELOCITY -> FeatureVector.create()
                        .put("historical_velocities", List.of(18, 22, 20));
                case M2_OVER_BUDGET, M2_REQUIREMENT_CHANGE ->
                        sprint(1.4, 0.3, 0.5);
                case M2_COMMUNICATION_COLLABORATION ->
                        FeatureVector.create()
                                .putNumber("avg_response_time", 36.0)
                                .putNumber("comments_per_task", 1.5)
                                .putNumber("collaboration_task_count", 10)
                                .putNumber("inactive_days", 5)
                                .putNumber("longest_silence_days", 3)
                                .putNumber("blockers", 2)
                                .putNumber("reopened_tasks", 1)
                                .putNumber("unanswered_threads", 2);
                case M3_ALLOCATION -> FeatureVector.create()
                        .putNumber("required_skill_count", 1).putNumber("skill_match_ratio", 0.5)
                        .putNumber("consistency_score", 0.6).putNumber("current_load_pct", 120)
                        .putNumber("avg_velocity", 6).putNumber("availability_rate", 1.0);
                case M4_COST_INITIAL, M4_COST_MID -> FeatureVector.create()
                        .putNumber("capacity_hours", BigDecimal.valueOf(300))
                        .putNumber("blended_hourly_rate", BigDecimal.valueOf(75))
                        .putNumber("overtime_hours", BigDecimal.valueOf(12));
            };

            AiModelClient.InferenceResult result = run(code, features);

            assertThat(result.status().isUsable()).as("%s should answer", code).isTrue();
            assertThat(result.drivers()).as("%s drivers", code).isNotEmpty();
            assertThat(result.explanation()).as("%s explanation", code).isNotBlank();
            assertThat(result.confidence()).as("%s confidence", code)
                    .isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        }
    }

    @Test
    @DisplayName("drivers are ordered by how much they moved the answer")
    void driversAreRankedByInfluence() {
        AiModelClient.InferenceResult result = run(AiModelCode.M1_STORY_POINTS, story(9, 600, 4, true));

        List<PredictionDriver> drivers = result.drivers();
        for (int index = 1; index < drivers.size(); index++) {
            assertThat(drivers.get(index - 1).contribution().abs())
                    .isGreaterThanOrEqualTo(drivers.get(index).contribution().abs());
        }
    }
}
