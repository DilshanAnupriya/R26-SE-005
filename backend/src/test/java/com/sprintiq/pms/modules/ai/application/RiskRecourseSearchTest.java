package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.AiPrediction;
import com.sprintiq.pms.modules.ai.domain.AiPredictionRepository;
import com.sprintiq.pms.modules.ai.domain.InferenceMode;
import com.sprintiq.pms.modules.ai.domain.PredictionDriver;
import com.sprintiq.pms.modules.ai.domain.PredictionStatus;
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
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counterfactual search: does it ask the model, and does it believe the answer?
 *
 * <p>The point of this component is that nothing about a step is written down in
 * advance — the target value and what it is worth are both read back out of the model.
 * These pin that by giving the search a model whose behaviour the test dictates, and
 * checking that the steps follow the model rather than any constant in the code.</p>
 *
 * <p>The stand-in model is a plain function of the feature map, so a test can say "this
 * sprint gets safer as the commitment falls" or "nothing helps" and see exactly what the
 * panel would then offer.</p>
 */
@DisplayName("Risk recourse search")
class RiskRecourseSearchTest {

    private static final UUID PREDICTION = UUID.randomUUID();
    private static final Instant SCORED_AT = Instant.parse("2026-08-25T09:00:00Z");

    @Test
    @DisplayName("reads the target value and the relief out of the model")
    void measuresRatherThanAsserts() {
        // A model that improves by one point of probability per point taken off the
        // commitment, from a baseline of 0.80 at 34 points.
        var search = searchOver(features -> 0.80 - (34 - number(features, "committed_story_points")) * 0.01);

        List<RiskRecourseSearch.Recourse> found = search.search(assessment(
                Map.of("committed_story_points", 34),
                PredictionDriver.up("committed_story_points", "points committed",
                        BigDecimal.valueOf(1.2), "34")));

        assertThat(found).hasSize(1);
        RiskRecourseSearch.Recourse step = found.get(0);

        // The lever's bound is 0.65, so the deepest rung is 34 × 0.65 ≈ 22 — and with a
        // model that keeps improving all the way down, that rung wins.
        assertThat(step.currentValue()).isEqualTo("34");
        assertThat(step.targetValue()).isEqualTo("22");
        // 34 → 22 is 12 points of commitment, so 0.12 of probability, so 12 ledger points.
        assertThat(step.points().doubleValue()).isCloseTo(12.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(step.title()).isEqualTo("Reduce the commitment from 34 to 22 points");
        assertThat(step.detail()).contains("80%").contains("68%").contains("measured");
    }

    @Test
    @DisplayName("offers nothing when the model does not reward the change")
    void theModelCanDecline() {
        // Flat: moving the commitment changes nothing the model cares about.
        var search = searchOver(features -> 0.80);

        assertThat(search.search(assessment(
                Map.of("committed_story_points", 34),
                PredictionDriver.up("committed_story_points", "points committed",
                        BigDecimal.valueOf(1.2), "34")))).isEmpty();
    }

    @Test
    @DisplayName("prefers a smaller change when the model likes it better")
    void picksTheBestRungNotTheDeepest() {
        // Non-monotone, as a boosted-tree model can be: cutting to about 26 is the
        // sweet spot and cutting further is worse. Taking the first improvement, or
        // assuming the bound is best, would recommend more work for less relief.
        var search = searchOver(features -> {
            double committed = number(features, "committed_story_points");
            return committed <= 23 ? 0.70 : committed <= 27 ? 0.50 : 0.80;
        });

        List<RiskRecourseSearch.Recourse> found = search.search(assessment(
                Map.of("committed_story_points", 34),
                PredictionDriver.up("committed_story_points", "points committed",
                        BigDecimal.valueOf(1.2), "34")));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).targetValue()).isEqualTo("26");
        assertThat(found.get(0).points().doubleValue()).isCloseTo(30.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    @DisplayName("leaves alone the readings a sprint cannot move")
    void skipsImmovableDrivers() {
        var search = searchOver(features -> 0.80 - (10 - number(features, "team_size")) * 0.05);

        // team_size raises the score and the model would happily reward shrinking the
        // team. It is not a lever, so nothing is offered — the search never probes it.
        assertThat(search.search(assessment(
                Map.of("team_size", 10),
                PredictionDriver.up("team_size", "team size",
                        BigDecimal.valueOf(0.9), "10")))).isEmpty();
    }

    @Test
    @DisplayName("ignores drivers that are already lowering the score")
    void skipsDriversArguingTheSprintIsFine() {
        var search = searchOver(features -> 0.80 - (34 - number(features, "committed_story_points")) * 0.01);

        assertThat(search.search(assessment(
                Map.of("committed_story_points", 34),
                PredictionDriver.down("committed_story_points", "points committed",
                        BigDecimal.valueOf(1.2), "34")))).isEmpty();
    }

    @Test
    @DisplayName("turns a flag into an instruction rather than a number")
    void booleanLeversReadAsEnglish() {
        var search = searchOver(features ->
                Boolean.TRUE.equals(features.get("has_sprint_goal")) ? 0.40 : 0.75);

        List<RiskRecourseSearch.Recourse> found = search.search(assessment(
                Map.of("has_sprint_goal", false),
                PredictionDriver.up("has_sprint_goal", "No sprint goal",
                        BigDecimal.valueOf(0.6), "none")));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).title()).isEqualTo("Put a sprint goal in place");
        assertThat(found.get(0).points().doubleValue()).isCloseTo(35.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    @DisplayName("says nothing when the assessment never recorded its features")
    void noFeaturesNoRecourse() {
        RiskAssessment assessment = assessment(Map.of(),
                PredictionDriver.up("committed_story_points", "points committed",
                        BigDecimal.valueOf(1.2), "34"));

        assertThat(searchOver(features -> 0.5).search(assessment)).isEmpty();
    }

    // ------------------------------------------------------------------- fixtures

    private static double number(Map<String, Object> features, String key) {
        Object value = features.get(key);
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /** A search whose model is the given function of the feature map. */
    private static RiskRecourseSearch searchOver(ToDoubleFunction<Map<String, Object>> model) {
        AiModelClient client = new AiModelClient() {
            @Override
            public String providerName() {
                return "test";
            }

            @Override
            public InferenceResult infer(InferenceRequest request) {
                return new InferenceResult(
                        BigDecimal.valueOf(model.applyAsDouble(request.features().asMap())),
                        "HIGH", BigDecimal.valueOf(0.8), null, null, List.of(), null,
                        Map.of(), PredictionStatus.SUCCESS, null);
            }
        };
        return new RiskRecourseSearch(client, predictionRepository());
    }

    private static RiskAssessment assessment(Map<String, Object> features,
                                             PredictionDriver... drivers) {
        RiskAssessment assessment = RiskAssessment.builder()
                .sprintId(UUID.randomUUID())
                .riskType(RiskType.OVER_BUDGET)
                .riskScore(BigDecimal.valueOf(0.80))
                .drivers(new ArrayList<>(List.of(drivers)))
                .predictionId(features.isEmpty() ? null : PREDICTION)
                .build();
        assessment.setCreatedAt(SCORED_AT);
        PAYLOAD.clear();
        PAYLOAD.putAll(features);
        return assessment;
    }

    /** What the stubbed repository hands back as the assessment's feature vector. */
    private static final Map<String, Object> PAYLOAD = new LinkedHashMap<>();

    /**
     * The prediction repository, as the one method the search calls.
     *
     * <p>A proxy rather than a class, because implementing {@code JpaRepository} by hand
     * to stub a single {@code findById} would be thirty methods of noise.</p>
     */
    private static AiPredictionRepository predictionRepository() {
        return (AiPredictionRepository) Proxy.newProxyInstance(
                AiPredictionRepository.class.getClassLoader(),
                new Class<?>[]{AiPredictionRepository.class},
                (proxy, method, args) -> {
                    if (!"findById".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    AiPrediction prediction = AiPrediction.builder()
                            .modelCode(AiModelCode.M2_OVER_BUDGET)
                            .modelVersion("test-1")
                            .mode(InferenceMode.ZERO_SHOT)
                            .requestPayload(new LinkedHashMap<>(PAYLOAD))
                            .build();
                    return Optional.of(prediction);
                });
    }
}
