package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.modules.ai.domain.PredictionDriver;
import com.sprintiq.pms.modules.ai.domain.RiskAssessment;
import com.sprintiq.pms.modules.ai.domain.RiskAssessmentRepository;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustment;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskRemediationStepResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The fix half of the risk screen: what to do about each driver, and what doing it is worth.
 *
 * <p>Every step here is derived from a driver the model actually reported, so the
 * checklist for a sprint is the checklist for <em>that</em> sprint's reasons rather than
 * a generic list of agile advice. A driver with no entry in the catalogue produces no
 * steps at all — see {@link RiskRemediationCatalogue} for why that silence is the point.</p>
 *
 * <p><strong>Ticking a step is a claim, and the credit for it is provisional.</strong>
 * Completion is recorded as a negative entry in the same ledger that charges a sprint for
 * a blocked task, so the gauge moves immediately and the reason is on the audit trail
 * next to everything else that moved it. What the ledger cannot do is verify the claim.
 * That is what <em>Assess</em> is for: re-scoring reads the board again, measures whether
 * the commitment really came down or the blockers really cleared, and the provisional
 * credit is replaced by whatever actually happened. A step ticked but not done therefore
 * pays back for a few hours and is then taken away — which is the correct behaviour, and
 * the reason no step is offered for a driver the model could not re-measure.</p>
 *
 * <p>Because the ledger is the record, the state and the credit are the same fact and
 * cannot disagree. There is no separate "step completed" table to fall out of step with
 * the number it is supposed to explain.</p>
 */
@Service
@RequiredArgsConstructor
public class RiskRemediationService {

    private final RiskAssessmentRepository assessments;
    private final SprintRiskAdjustmentService adjustmentService;

    /** Prefix on the ledger source key, so these are distinguishable from board events. */
    private static final String SOURCE_PREFIX = "STEP";

    /**
     * The most of a score a checklist may return.
     *
     * <p>Half, because the rest of what the model read is not a to-do item. Team size,
     * the domain, how much the project has churned historically — those are in the score
     * and no sprint-length action removes them. A checklist that could drive a risk to
     * zero would be claiming otherwise, and the first team to complete one and watch the
     * sprint fail anyway would stop believing the whole screen.</p>
     *
     * <p>Like every other number in this ledger it is an agreed convention rather than a
     * measurement, and the panel says so.</p>
     */
    private static final BigDecimal RELIEF_FRACTION = BigDecimal.valueOf(0.5);

    /** The ledger's scale: 100 points to a whole probability. */
    private static final BigDecimal POINTS_PER_UNIT = BigDecimal.valueOf(100);

    // -------------------------------------------------------------------- reads

    /** Every step on offer for a sprint, across all three risks, with its current state. */
    @Transactional(readOnly = true)
    public List<RiskRemediationStepResponse> stepsFor(UUID sprintId) {
        SprintRiskAdjustmentService.Ledger ledger = adjustmentService.ledgerFor(sprintId);
        List<RiskRemediationStepResponse> steps = new ArrayList<>();
        for (RiskType riskType : RiskType.values()) {
            steps.addAll(stepsFor(sprintId, riskType, ledger));
        }
        return steps;
    }

    // ------------------------------------------------------------------- writes

    /**
     * Marks a step done or not done, and returns the whole list as it now stands.
     *
     * <p>The step is re-derived from the stored assessment rather than trusted from the
     * request. A client that could name its own points could set any risk score it liked
     * by sending a large enough one, and the ledger would record it as evidence.</p>
     */
    @Transactional
    public List<RiskRemediationStepResponse> toggle(UUID sprintId, RiskType riskType,
                                                    String stepKey, boolean done) {
        SprintRiskAdjustmentService.Ledger ledger = adjustmentService.ledgerFor(sprintId);
        RiskRemediationStepResponse step = stepsFor(sprintId, riskType, ledger).stream()
                .filter(candidate -> candidate.stepKey().equals(stepKey))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException(
                        "No step '" + stepKey + "' is on offer for " + riskType
                        + " on this sprint. Re-assess the sprint — its drivers have moved."));

        String sourceKey = sourceKey(riskType, stepKey);
        if (done) {
            adjustmentService.restate(sprintId, riskType, sourceKey,
                    step.points().negate(), "Fix applied: " + step.title());
        } else {
            adjustmentService.clear(sprintId, sourceKey);
        }
        return stepsFor(sprintId);
    }

    // ---------------------------------------------------------------- internals

    /**
     * One risk's steps, in the order the explanation panel ranks its drivers.
     *
     * <p>Only the drivers that <em>raise</em> the score. A driver arguing the sprint is
     * fine needs no remedy, and offering one would suggest the model wanted it changed.</p>
     */
    private List<RiskRemediationStepResponse> stepsFor(UUID sprintId, RiskType riskType,
                                                       SprintRiskAdjustmentService.Ledger ledger) {
        RiskAssessment assessment = assessments
                .findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(sprintId, riskType)
                .orElse(null);
        if (assessment == null || assessment.getDrivers() == null) {
            return List.of();
        }

        List<PredictionDriver> raisers = assessment.getDrivers().stream()
                .filter(driver -> PredictionDriver.INCREASE.equals(driver.direction()))
                .filter(driver -> driver.contribution() != null
                        && driver.contribution().signum() != 0)
                .sorted((left, right) -> right.contribution().abs()
                        .compareTo(left.contribution().abs()))
                .toList();

        // The denominator is every raising driver, not only the ones with steps. A sprint
        // whose biggest problem is its team size should not have that share of the score
        // redistributed onto the two things a checklist can reach — the checklist would
        // then promise relief for a driver it never touches.
        BigDecimal mass = raisers.stream()
                .map(driver -> driver.contribution().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (mass.signum() == 0) {
            return List.of();
        }

        BigDecimal score = assessment.getRiskScore() == null
                ? BigDecimal.ZERO : assessment.getRiskScore();
        List<RiskRemediationStepResponse> steps = new ArrayList<>();

        for (PredictionDriver driver : raisers) {
            List<RiskRemediationCatalogue.Step> recipe =
                    RiskRemediationCatalogue.forFeature(driver.feature());
            if (recipe.isEmpty()) {
                continue;
            }
            // What this driver is worth: its share of the raising explanation, applied to
            // the half of the model's own figure a checklist is allowed to reach.
            BigDecimal relief = driver.contribution().abs()
                    .divide(mass, 6, RoundingMode.HALF_UP)
                    .multiply(RELIEF_FRACTION)
                    .multiply(score)
                    .multiply(POINTS_PER_UNIT);

            for (RiskRemediationCatalogue.Step recipeStep : recipe) {
                // At least one point. A step worth nothing is one nobody will do, and a
                // checklist that renders "0 pts" beside real advice reads as broken.
                BigDecimal points = relief.multiply(BigDecimal.valueOf(recipeStep.weight()))
                        .setScale(0, RoundingMode.HALF_UP)
                        .max(BigDecimal.ONE);

                String stepKey = driver.feature() + ":" + recipeStep.key();
                Optional<SprintRiskAdjustment> standing = ledger.standing(
                        sourceKey(riskType, stepKey), assessment.getCreatedAt());

                steps.add(new RiskRemediationStepResponse(
                        riskType, driver.feature(), driver.label(), driver.detail(),
                        stepKey, recipeStep.title(), recipeStep.detail(), points,
                        standing.isPresent(),
                        standing.map(SprintRiskAdjustment::getCreatedAt).orElse(null)));
            }
        }
        return steps;
    }

    private static String sourceKey(RiskType riskType, String stepKey) {
        return SOURCE_PREFIX + ":" + riskType.name() + ":" + stepKey;
    }
}
