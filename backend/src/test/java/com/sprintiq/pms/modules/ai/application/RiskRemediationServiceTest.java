package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.modules.ai.domain.PredictionDriver;
import com.sprintiq.pms.modules.ai.domain.RiskAssessment;
import com.sprintiq.pms.modules.ai.domain.RiskAssessmentRepository;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustment;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskRemediationStepResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the fix list offers, what it is worth, and what ticking one does.
 *
 * <p>Three properties carry the feature and each is easy to break by tuning something
 * that looks unrelated: the steps belong to the drivers the model actually reported, the
 * points are a share of the model's own figure rather than a number the client chose,
 * and completing a step lands in the ledger as a negative entry — which is the only
 * reason the gauge moves.</p>
 *
 * <p>Hand-written fakes rather than mocks, following {@code TaskReopenRiskTest}: the
 * assertions want the arguments themselves, and it keeps the test off the inline mock
 * maker, which the JDK this builds on does not support.</p>
 */
@DisplayName("Risk remediation steps")
class RiskRemediationServiceTest {

    private static final UUID SPRINT = UUID.randomUUID();
    private static final Instant SCORED_AT = Instant.parse("2026-08-25T09:00:00Z");

    private Ledger ledger;
    private RiskAssessment assessment;
    private RiskRemediationService service;

    @BeforeEach
    void setUp() {
        // 130% of velocity, four blocked tasks, and a team size nothing can change this
        // week — deliberately one driver of each kind the panel has to handle.
        assessment = assessment(BigDecimal.valueOf(0.80), List.of(
                PredictionDriver.up("commitment_ratio", "Commitment vs velocity",
                        BigDecimal.valueOf(1.2), "Committed 130% of what the team usually delivers"),
                PredictionDriver.up("blocked_count", "Blocked work",
                        BigDecimal.valueOf(0.6), "4 items blocked"),
                PredictionDriver.up("team_size", "Team size",
                        BigDecimal.valueOf(0.2), "3 people"),
                PredictionDriver.down("velocity_trend", "Velocity trend",
                        BigDecimal.valueOf(0.4), "rising")));
        ledger = new Ledger();
        service = new RiskRemediationService(repositoryReturning(assessment), ledger);
    }

    private List<RiskRemediationStepResponse> steps() {
        return service.stepsFor(SPRINT).stream()
                .filter(step -> step.riskType() == RiskType.OVER_BUDGET)
                .toList();
    }

    @Test
    @DisplayName("offers steps only for the drivers a sprint can actually move")
    void onlyForActionableDrivers() {
        List<String> features = steps().stream()
                .map(RiskRemediationStepResponse::driverFeature).distinct().toList();

        assertThat(features).containsExactly("commitment_ratio", "blocked_count");
        // team_size raises the score and has no remedy; velocity_trend lowers it and needs
        // none. Offering either would be advice the model never asked for.
        assertThat(features).doesNotContain("team_size", "velocity_trend");
    }

    @Test
    @DisplayName("the strongest driver carries the most relief, and never more than its share")
    void pointsFollowTheExplanation() {
        List<RiskRemediationStepResponse> steps = steps();

        BigDecimal commitment = totalFor(steps, "commitment_ratio");
        BigDecimal blocked = totalFor(steps, "blocked_count");
        assertThat(commitment).isGreaterThan(blocked);

        // Half of an 80% score is 40 points to share out. commitment_ratio is 1.2 of the
        // 2.0 raising mass, so 24 of them; blocked_count 0.6, so 12. The 0.2 sitting on
        // team_size stays with team_size rather than being redistributed onto the two
        // drivers a checklist can reach.
        assertThat(commitment.intValue()).isEqualTo(24);
        assertThat(blocked.intValue()).isEqualTo(12);

        BigDecimal everything = steps.stream().map(RiskRemediationStepResponse::points)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(everything.doubleValue()).isLessThan(0.80 * 100);
    }

    @Test
    @DisplayName("completing a step credits the ledger against that risk")
    void completingCreditsTheLedger() {
        RiskRemediationStepResponse step = steps().get(0);

        service.toggle(SPRINT, RiskType.OVER_BUDGET, step.stepKey(), true);

        assertThat(ledger.written).hasSize(1);
        Entry entry = ledger.written.get(0);
        assertThat(entry.riskType()).isEqualTo(RiskType.OVER_BUDGET);
        // Negative: the ledger charges a sprint for a blocked task and refunds it here,
        // in the same units, on the same audit trail.
        assertThat(entry.delta()).isEqualByComparingTo(step.points().negate());
        assertThat(entry.sourceKey()).isEqualTo("STEP:OVER_BUDGET:" + step.stepKey());
        assertThat(entry.reason()).contains(step.title());
    }

    @Test
    @DisplayName("a completed step reads as done, and unticking withdraws it")
    void doneStateFollowsTheLedger() {
        RiskRemediationStepResponse step = steps().get(0);
        ledger.standing(step.stepKey(), SCORED_AT.plusSeconds(60));

        assertThat(find(steps(), step.stepKey()).done()).isTrue();

        service.toggle(SPRINT, RiskType.OVER_BUDGET, step.stepKey(), false);
        assertThat(ledger.cleared).containsExactly("STEP:OVER_BUDGET:" + step.stepKey());
    }

    @Test
    @DisplayName("a step ticked before the last scoring no longer counts as done")
    void creditExpiresAtTheNextAssessment() {
        RiskRemediationStepResponse step = steps().get(0);
        // Claimed, then the sprint was re-assessed: the model has since read the board
        // and measured whatever the step achieved, so the provisional credit is spent.
        ledger.standing(step.stepKey(), SCORED_AT.minus(Duration.ofHours(2)));

        assertThat(find(steps(), step.stepKey()).done()).isFalse();
    }

    @Test
    @DisplayName("refuses a step the sprint is not currently offering")
    void rejectsUnknownSteps() {
        assertThatThrownBy(() ->
                service.toggle(SPRINT, RiskType.OVER_BUDGET, "team_size:hire-someone", true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("team_size:hire-someone");
        assertThat(ledger.written).isEmpty();
    }

    @Test
    @DisplayName("an unassessed sprint offers nothing")
    void nothingWithoutAnAssessment() {
        RiskRemediationService bare =
                new RiskRemediationService(repositoryReturning(null), new Ledger());
        assertThat(bare.stepsFor(SPRINT)).isEmpty();
    }

    // ------------------------------------------------------------------- fixtures

    private static BigDecimal totalFor(List<RiskRemediationStepResponse> steps, String feature) {
        return steps.stream().filter(step -> step.driverFeature().equals(feature))
                .map(RiskRemediationStepResponse::points)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static RiskRemediationStepResponse find(List<RiskRemediationStepResponse> steps,
                                                    String stepKey) {
        return steps.stream().filter(step -> step.stepKey().equals(stepKey)).findFirst()
                .orElseThrow(() -> new AssertionError("No step " + stepKey));
    }

    private static RiskAssessment assessment(BigDecimal score, List<PredictionDriver> drivers) {
        RiskAssessment assessment = RiskAssessment.builder()
                .sprintId(SPRINT)
                .riskType(RiskType.OVER_BUDGET)
                .riskScore(score)
                .drivers(new ArrayList<>(drivers))
                .build();
        assessment.setCreatedAt(SCORED_AT);
        return assessment;
    }

    /**
     * The repository, as the one method this service calls.
     *
     * <p>A proxy rather than a class, because implementing {@code JpaRepository} by hand
     * to stub a single finder would be thirty methods of noise around one line that
     * matters.</p>
     */
    private static RiskAssessmentRepository repositoryReturning(RiskAssessment assessment) {
        return (RiskAssessmentRepository) Proxy.newProxyInstance(
                RiskAssessmentRepository.class.getClassLoader(),
                new Class<?>[]{RiskAssessmentRepository.class},
                (proxy, method, args) -> {
                    if (!"findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    // Only the over-budget model is fixtured; the other two are correctly
                    // absent, which is also what an unassessed risk looks like.
                    return assessment != null && args[1] == RiskType.OVER_BUDGET
                            ? Optional.of(assessment) : Optional.empty();
                });
    }

    /** One ledger write, flattened to what the assertions read. */
    private record Entry(RiskType riskType, String sourceKey, BigDecimal delta, String reason) {}

    private static final class Ledger extends SprintRiskAdjustmentService {

        private final List<Entry> written = new ArrayList<>();
        private final List<String> cleared = new ArrayList<>();
        private final List<SprintRiskAdjustment> entries = new ArrayList<>();

        Ledger() {
            // The repository is never reached: every method the service calls is
            // overridden here.
            super(null);
        }

        /** Pretends a step was ticked at the given moment. */
        void standing(String stepKey, Instant at) {
            SprintRiskAdjustment entry = SprintRiskAdjustment.builder()
                    .sprintId(SPRINT)
                    .riskType(RiskType.OVER_BUDGET)
                    .delta(BigDecimal.valueOf(-5))
                    .reason("Fix applied")
                    .sourceKey("STEP:OVER_BUDGET:" + stepKey)
                    .build();
            entry.setCreatedAt(at);
            entries.add(entry);
        }

        @Override
        public SprintRiskAdjustmentService.Ledger ledgerFor(UUID sprintId) {
            return new SprintRiskAdjustmentService.Ledger(List.copyOf(entries));
        }

        @Override
        public void restate(UUID sprintId, RiskType riskType, String sourceKey,
                            BigDecimal delta, String reason) {
            written.add(new Entry(riskType, sourceKey, delta, reason));
        }

        @Override
        public void clear(UUID sprintId, String sourceKey) {
            cleared.add(sourceKey);
        }
    }
}
