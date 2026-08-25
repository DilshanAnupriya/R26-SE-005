package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.AiPrediction;
import com.sprintiq.pms.modules.ai.domain.AiPredictionRepository;
import com.sprintiq.pms.modules.ai.domain.InferenceMode;
import com.sprintiq.pms.modules.ai.domain.PredictionDriver;
import com.sprintiq.pms.modules.ai.domain.RiskAssessment;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search against a real model, rather than a function a test wrote.
 *
 * <p>{@link RiskRecourseSearchTest} dictates the model's behaviour to prove the search
 * follows it. This does the opposite: it points the search at the heuristic the product
 * actually ships for development and demos, and checks that what comes back is advice a
 * person could act on. The two together are the claim — the steps follow the model, and
 * the model this is wired to produces sensible ones.</p>
 *
 * <p>The mock client rather than the CatBoost bundle because {@code AI_PROVIDER=mock} is
 * what a developer, a demo and this test suite all run against, and because a unit test
 * that loaded a pickled model would be testing the bundle rather than the search.</p>
 */
@DisplayName("Recourse against the shipped heuristic")
class RiskRecourseAgainstMockModelTest {

    /** An over-committed sprint with four blocked items — the case the panel exists for. */
    private static Map<String, Object> overCommittedSprint() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("commitment_ratio", 1.35);
        features.put("blocked_count", 4);
        features.put("story_count", 12);
        features.put("effort_burn_ratio", 0.55);
        features.put("elapsed_fraction", 0.5);
        features.put("scope_volatility", 0.0);
        features.put("overtime_minutes", 0);
        features.put("effort_minutes", 4800);
        features.put("task_slippage_rate", 0.0);
        features.put("prev_reopened_tasks", 0);
        return features;
    }

    @Test
    @DisplayName("finds the commitment and the blockers, and prices both from the model")
    void findsActionableStepsOnARealSprint() {
        List<RiskRecourseSearch.Recourse> found = searchOn(overCommittedSprint(),
                PredictionDriver.up("commitment_ratio", "Commitment vs velocity",
                        BigDecimal.valueOf(0.84), "Committed 135%"),
                PredictionDriver.up("blocked_count", "Blocked work",
                        BigDecimal.valueOf(0.73), "33% of items are blocked"));

        assertThat(found).extracting(RiskRecourseSearch.Recourse::feature)
                .containsExactlyInAnyOrder("commitment_ratio", "blocked_count");

        for (RiskRecourseSearch.Recourse step : found) {
            // Every step has to be a sentence with real numbers in it, and a fall the
            // model actually reported — not a placeholder either side of the arrow.
            assertThat(step.title()).startsWith("Reduce ").contains(" from ").contains(" to ");
            assertThat(step.points().doubleValue()).isGreaterThanOrEqualTo(1.0);
            assertThat(step.projectedScore()).isLessThan(step.baselineScore());
            assertThat(step.detail()).contains("measured");
        }

        // Ordered by what the model says they buy, so the panel leads with the one that
        // moves the number most.
        assertThat(found.get(0).points()).isGreaterThanOrEqualTo(found.get(1).points());
    }

    @Test
    @DisplayName("offers nothing on a sprint the model is already happy with")
    void quietSprintGetsNoChecklist() {
        Map<String, Object> healthy = overCommittedSprint();
        healthy.put("commitment_ratio", 0.85);
        healthy.put("blocked_count", 0);

        // The mock adds nothing for a commitment under 1.0 or for zero blocked items, so
        // there is no fall to find and nothing to propose.
        assertThat(searchOn(healthy,
                PredictionDriver.up("commitment_ratio", "Commitment vs velocity",
                        BigDecimal.valueOf(0.1), "Committed 85%"))).isEmpty();
    }

    // ------------------------------------------------------------------- fixtures

    private static List<RiskRecourseSearch.Recourse> searchOn(Map<String, Object> features,
                                                              PredictionDriver... drivers) {
        RiskAssessment assessment = RiskAssessment.builder()
                .sprintId(UUID.randomUUID())
                .riskType(RiskType.OVER_BUDGET)
                .riskScore(BigDecimal.valueOf(0.7))
                .drivers(new ArrayList<>(List.of(drivers)))
                .predictionId(UUID.randomUUID())
                .build();
        assessment.setCreatedAt(Instant.parse("2026-08-25T09:00:00Z"));

        return new RiskRecourseSearch(new MockAiModelClient(), repositoryFor(features))
                .search(assessment);
    }

    private static AiPredictionRepository repositoryFor(Map<String, Object> features) {
        return (AiPredictionRepository) Proxy.newProxyInstance(
                AiPredictionRepository.class.getClassLoader(),
                new Class<?>[]{AiPredictionRepository.class},
                (proxy, method, args) -> {
                    if (!"findById".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return Optional.of(AiPrediction.builder()
                            .modelCode(AiModelCode.M2_OVER_BUDGET)
                            .modelVersion("mock-1")
                            .mode(InferenceMode.ZERO_SHOT)
                            .requestPayload(new LinkedHashMap<>(features))
                            .build());
                });
    }
}
