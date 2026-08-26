package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.modules.ai.application.RiskRecourseLevers.Action;
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
 * What the panel does with what the search found.
 *
 * <p>The search decides what the steps are and what they are worth
 * ({@link RiskRecourseSearchTest}); this is the half that remembers which of them a team
 * has claimed and turns a claim into a number on the gauge. Three properties carry it:
 * the points reach the ledger as a credit rather than a charge, a claim survives a
 * re-assessment and is settled against it rather than cancelled by it, and a step nobody
 * offered cannot be claimed at all.</p>
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
    @DisplayName("a step ticked before the last scoring still reads as done")
    void claimSurvivesTheNextAssessment() {
        // Claimed, and then the sprint was re-assessed. The claim outlives the cutoff:
        // its credit is settled against the new scoring rather than cancelled by it, so
        // the tick and the points it is holding off stay the same fact.
        ledger.standing("committed_story_points", SCORED_AT.minus(Duration.ofHours(2)));

        assertThat(find(steps(), "committed_story_points").done()).isTrue();
    }

    @Test
    @DisplayName("settling re-prices a claim the model is still offering")
    void settlingRepricesWhatIsStillOnOffer() {
        // Ticked, then Assess. Nothing on the board has moved, so the same step comes
        // back on offer and the claim goes on paying — at the new scoring's price,
        // which is the figure the panel is showing beside it.
        ledger.standing("committed_story_points", SCORED_AT.minus(Duration.ofHours(2)));

        service.settleAfterScoring(SPRINT);

        assertThat(ledger.cleared).isEmpty();
        assertThat(ledger.repriced).hasSize(1);
        Repriced repriced = ledger.repriced.get(0);
        assertThat(repriced.sourceKey()).isEqualTo("STEP:OVER_BUDGET:committed_story_points");
        assertThat(repriced.delta()).isEqualByComparingTo(BigDecimal.valueOf(-12));
        assertThat(repriced.reason()).contains("Reduce the commitment from 34 to 22");
    }

    @Test
    @DisplayName("settling withdraws a claim the model has stopped offering")
    void settlingWithdrawsWhatTheModelAbsorbed() {
        ledger.standing("committed_story_points", SCORED_AT.minus(Duration.ofHours(2)));

        // The commitment really did come down, so the new scoring no longer names it as
        // a reading raising the score. The improvement is in the model's own figure now,
        // and a claim still paying for it would take the same points off twice.
        RiskRemediationService afterTheWorkLanded = new RiskRemediationService(
                repositoryReturning(assessment("blocked_count")), ledger,
                searchReturning(recourse("blocked_count", "blocked items", "4", "1", 0.68, 0.62)));
        afterTheWorkLanded.settleAfterScoring(SPRINT);

        assertThat(ledger.cleared).containsExactly("STEP:OVER_BUDGET:committed_story_points");
        assertThat(ledger.repriced).isEmpty();
    }

    @Test
    @DisplayName("settling leaves claims alone when the search comes back empty")
    void settlingSurvivesASilentModel() {
        ledger.standing("committed_story_points", SCORED_AT.minus(Duration.ofHours(2)));

        // An unreachable model service, or one whose probes blew their budget, returns no
        // steps at all. The assessment still blames the reading, so the claim is still
        // owed: it keeps the value it had, and is neither withdrawn nor guessed at. This
        // is why the release test reads the stored drivers rather than the search - a
        // busy model service must not be able to cancel a team's credit.
        RiskRemediationService silent = new RiskRemediationService(
                repositoryReturning(assessment()), ledger, searchReturning());
        silent.settleAfterScoring(SPRINT);

        assertThat(ledger.cleared).isEmpty();
        assertThat(ledger.repriced).isEmpty();
    }

    @Test
    @DisplayName("settling withdraws nothing while the reading is still raising the score")
    void settlingHoldsEveryClaimTheModelStillBlames() {
        ledger.standing("committed_story_points", SCORED_AT.minus(Duration.ofHours(2)));
        ledger.standing("blocked_count", SCORED_AT.minus(Duration.ofHours(2)));

        service.settleAfterScoring(SPRINT);

        assertThat(ledger.cleared).isEmpty();
        assertThat(ledger.repriced).extracting(Repriced::sourceKey)
                .containsExactlyInAnyOrder("STEP:OVER_BUDGET:committed_story_points",
                        "STEP:OVER_BUDGET:blocked_count");
    }

    @Test
    @DisplayName("settling an unclaimed sprint touches nothing")
    void settlingIsFreeWithoutClaims() {
        service.settleAfterScoring(SPRINT);

        assertThat(ledger.cleared).isEmpty();
        assertThat(ledger.repriced).isEmpty();
        assertThat(ledger.written).isEmpty();
    }

    @Test
    @DisplayName("a claimed step stays on the list when the model cannot price it")
    void claimedStepsSurviveASilentSearch() {
        ledger.standing("committed_story_points", SCORED_AT.minus(Duration.ofHours(2)));

        // Every probe failing is what an unreachable model service looks like from here,
        // and it used to empty the checklist - taking the team's ticks off screen along
        // with it, so the gauge went on paying for a claim with no checkbox to reverse.
        RiskRemediationService silent = new RiskRemediationService(
                repositoryReturning(assessment()), ledger, searchReturning());

        assertThat(silent.stepsFor(SPRINT)).singleElement().satisfies(step -> {
            assertThat(step.stepKey()).isEqualTo("committed_story_points");
            assertThat(step.done()).isTrue();
            // The two facts the row has to carry without the model: what was claimed,
            // and what it is still worth. Both come off the ledger entry itself.
            assertThat(step.title()).isEqualTo("Reduce the commitment from 34 to 22 points");
            assertThat(step.points()).isEqualByComparingTo(BigDecimal.valueOf(12));
            assertThat(step.doneAt()).isEqualTo(SCORED_AT.minus(Duration.ofHours(2)));
            // Not measured by this scoring, so not asserted as if it had been.
            assertThat(step.baselineScore()).isNull();
            assertThat(step.projectedScore()).isNull();
        });
    }

    @Test
    @DisplayName("a claim the search did price is not listed twice")
    void claimedStepsAreNotDuplicated() {
        ledger.standing("committed_story_points", SCORED_AT.minus(Duration.ofHours(2)));

        assertThat(steps()).extracting(RiskRemediationStepResponse::stepKey)
                .containsExactlyInAnyOrder("committed_story_points", "blocked_count");
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

    private static RiskAssessment assessment(String... raisingFeatures) {
        List<PredictionDriver> drivers = new ArrayList<>();
        for (String feature : raisingFeatures) {
            drivers.add(PredictionDriver.up(feature, feature, BigDecimal.valueOf(0.5), "reading"));
        }
        RiskAssessment assessment = RiskAssessment.builder()
                .sprintId(SPRINT)
                .riskType(RiskType.OVER_BUDGET)
                .riskScore(BigDecimal.valueOf(0.80))
                .drivers(drivers)
                .build();
        assessment.setCreatedAt(SCORED_AT);
        return assessment;
    }

    /** The two readings the fixture search offers steps against. */
    private static RiskAssessment assessment() {
        return assessment("committed_story_points", "blocked_count");
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

    /** One re-pricing, which revalues an existing entry without re-dating it. */
    private record Repriced(String sourceKey, BigDecimal delta, String reason) {}

    private static final class Ledger extends SprintRiskAdjustmentService {

        private final List<Entry> written = new ArrayList<>();
        private final List<String> cleared = new ArrayList<>();
        private final List<Repriced> repriced = new ArrayList<>();
        private final List<SprintRiskAdjustment> entries = new ArrayList<>();

        Ledger() {
            // The repository is never reached: every method the service calls is
            // overridden here.
            super(null);
        }

        /** Pretends a step was ticked at the given moment, as the service records it. */
        void standing(String stepKey, Instant at) {
            SprintRiskAdjustment entry = SprintRiskAdjustment.builder()
                    .sprintId(SPRINT)
                    .riskType(RiskType.OVER_BUDGET)
                    .delta(BigDecimal.valueOf(-12))
                    .reason("Fix applied: Reduce the commitment from 34 to 22 points")
                    .sourceKey("STEP:OVER_BUDGET:" + stepKey)
                    .survivesScoring(true)
                    .build();
            entry.setCreatedAt(at);
            entries.add(entry);
        }

        @Override
        public SprintRiskAdjustmentService.Ledger ledgerFor(UUID sprintId) {
            return new SprintRiskAdjustmentService.Ledger(List.copyOf(entries));
        }

        @Override
        public void restateStanding(UUID sprintId, RiskType riskType, String sourceKey,
                                    BigDecimal delta, String reason) {
            written.add(new Entry(riskType, sourceKey, delta, reason));
        }

        @Override
        public void reprice(UUID sprintId, String sourceKey, BigDecimal delta, String reason) {
            repriced.add(new Repriced(sourceKey, delta, reason));
        }

        @Override
        public void clear(UUID sprintId, String sourceKey) {
            cleared.add(sourceKey);
        }
    }
}
