package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.modules.ai.application.RiskRecourseLevers.Action;
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
 * What the panel does with what the search found.
 *
 * <p>The search decides what the steps are and what they are worth
 * ({@link RiskRecourseSearchTest}); this is the half that remembers which of them a team
 * has claimed and turns a claim into a number on the gauge. Three properties carry it:
 * the points reach the ledger as a credit rather than a charge, a claim expires when the
 * sprint is re-assessed, and a step nobody offered cannot be claimed at all.</p>
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
    private RiskRemediationService service;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        service = new RiskRemediationService(
                repositoryReturning(assessment()), ledger,
                searchReturning(
                        recourse("committed_story_points", "the commitment", "34", "22",
                                0.80, 0.68),
                        recourse("blocked_count", "blocked items", "4", "1", 0.80, 0.74)));
    }

    private List<RiskRemediationStepResponse> steps() {
        return service.stepsFor(SPRINT).stream()
                .filter(step -> step.riskType() == RiskType.OVER_BUDGET)
                .toList();
    }

    @Test
    @DisplayName("passes the search's measurement through untouched")
    void reportsWhatTheModelMeasured() {
        RiskRemediationStepResponse step = find(steps(), "committed_story_points");

        assertThat(step.title()).isEqualTo("Reduce the commitment from 34 to 22 points");
        assertThat(step.currentValue()).isEqualTo("34");
        assertThat(step.targetValue()).isEqualTo("22");
        // 0.80 → 0.68 is twelve points of probability, and twelve ledger points. Nothing
        // in this service apportions or rescales it.
        assertThat(step.points()).isEqualByComparingTo(BigDecimal.valueOf(12));
        assertThat(step.baselineScore()).isEqualByComparingTo(BigDecimal.valueOf(0.80));
        assertThat(step.projectedScore()).isEqualByComparingTo(BigDecimal.valueOf(0.68));
    }

    @Test
    @DisplayName("completing a step credits the ledger against that risk")
    void completingCreditsTheLedger() {
        service.toggle(SPRINT, RiskType.OVER_BUDGET, "committed_story_points", true);

        assertThat(ledger.written).hasSize(1);
        Entry entry = ledger.written.get(0);
        assertThat(entry.riskType()).isEqualTo(RiskType.OVER_BUDGET);
        // Negative: the ledger charges a sprint for a blocked task and refunds it here,
        // in the same units, on the same audit trail.
        assertThat(entry.delta()).isEqualByComparingTo(BigDecimal.valueOf(-12));
        assertThat(entry.sourceKey()).isEqualTo("STEP:OVER_BUDGET:committed_story_points");
        assertThat(entry.reason()).contains("Reduce the commitment from 34 to 22");
    }

    @Test
    @DisplayName("a completed step reads as done, and unticking withdraws it")
    void doneStateFollowsTheLedger() {
        ledger.standing("committed_story_points", SCORED_AT.plusSeconds(60));

        assertThat(find(steps(), "committed_story_points").done()).isTrue();
        assertThat(find(steps(), "blocked_count").done()).isFalse();

        service.toggle(SPRINT, RiskType.OVER_BUDGET, "committed_story_points", false);
        assertThat(ledger.cleared).containsExactly("STEP:OVER_BUDGET:committed_story_points");
    }

    @Test
    @DisplayName("a step ticked before the last scoring no longer counts as done")
    void creditExpiresAtTheNextAssessment() {
        // Claimed, then the sprint was re-assessed: the model has since read the board
        // and measured whatever the step achieved, so the provisional credit is spent.
        ledger.standing("committed_story_points", SCORED_AT.minus(Duration.ofHours(2)));

        assertThat(find(steps(), "committed_story_points").done()).isFalse();
    }

    @Test
    @DisplayName("refuses a step the model is not currently offering")
    void rejectsUnknownSteps() {
        assertThatThrownBy(() -> service.toggle(SPRINT, RiskType.OVER_BUDGET, "team_size", true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("team_size");
        assertThat(ledger.written).isEmpty();
    }

    @Test
    @DisplayName("an unassessed sprint offers nothing")
    void nothingWithoutAnAssessment() {
        RiskRemediationService bare = new RiskRemediationService(
                repositoryReturning(null), new Ledger(), searchReturning());
        assertThat(bare.stepsFor(SPRINT)).isEmpty();
    }

    @Test
    @DisplayName("a sprint whose model rewards nothing offers nothing")
    void nothingWhenTheModelDeclines() {
        RiskRemediationService quiet = new RiskRemediationService(
                repositoryReturning(assessment()), new Ledger(), searchReturning());
        assertThat(quiet.stepsFor(SPRINT)).isEmpty();
    }

    // ------------------------------------------------------------------- fixtures

    private static RiskRemediationStepResponse find(List<RiskRemediationStepResponse> steps,
                                                    String stepKey) {
        return steps.stream().filter(step -> step.stepKey().equals(stepKey)).findFirst()
                .orElseThrow(() -> new AssertionError("No step " + stepKey));
    }

    private static RiskRecourseSearch.Recourse recourse(String feature, String noun,
                                                        String current, String target,
                                                        double baseline, double projected) {
        return new RiskRecourseSearch.Recourse(feature, noun, Action.REDUCE, noun,
                feature.equals("committed_story_points") ? "points" : "",
                current, target,
                BigDecimal.valueOf(baseline), BigDecimal.valueOf(projected),
                BigDecimal.valueOf((baseline - projected) * 100).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /** The search, as a fixed set of findings. */
    private static RiskRecourseSearch searchReturning(RiskRecourseSearch.Recourse... found) {
        return new RiskRecourseSearch(null, null) {
            @Override
            public List<RiskRecourseSearch.Recourse> search(RiskAssessment assessment) {
                return List.of(found);
            }
        };
    }

    private static RiskAssessment assessment() {
        RiskAssessment assessment = RiskAssessment.builder()
                .sprintId(SPRINT)
                .riskType(RiskType.OVER_BUDGET)
                .riskScore(BigDecimal.valueOf(0.80))
                .drivers(new ArrayList<>())
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
                    .delta(BigDecimal.valueOf(-12))
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
