package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The fix half of the risk screen: what to do about each driver, and what doing it is worth.
 *
 * <p>Nothing here decides what the steps are. {@link RiskRecourseSearch} derives them by
 * re-running the trained model with one reading moved at a time and measuring the fall in
 * the prediction; this service is the part that remembers which of them a team has
 * claimed, and turns a claim into a number on the gauge.</p>
 *
 * <p><strong>Ticking a step is a claim, and the credit for it is provisional.</strong>
 * Completion is recorded as a negative entry in the same ledger that charges a sprint for
 * a blocked task, so the gauge moves immediately and the reason is on the audit trail
 * next to everything else that moved it. What the ledger cannot do is verify the claim.
 * That is what <em>Assess</em> is for: re-scoring reads the board again, measures whether
 * the commitment really came down or the blockers really cleared, and the provisional
 * credit is replaced by whatever actually happened. A step ticked but not done therefore
 * pays back for a few hours and is then taken away — which is the correct behaviour, and
 * the reason every step names a reading the model will re-read.</p>
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
    private final RiskRecourseSearch recourse;

    /** Prefix on the ledger source key, so these are distinguishable from board events. */
    private static final String SOURCE_PREFIX = "STEP";

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
     * <p>The step is re-derived from the model rather than trusted from the request. A
     * client that could name its own points could set any risk score it liked by sending
     * a large enough one, and the ledger would record it as evidence.</p>
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
     * One risk's steps: the search's findings, with the ledger's answer to "has anyone
     * claimed this" laid over them.
     */
    private List<RiskRemediationStepResponse> stepsFor(UUID sprintId, RiskType riskType,
                                                       SprintRiskAdjustmentService.Ledger ledger) {
        RiskAssessment assessment = assessments
                .findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(sprintId, riskType)
                .orElse(null);
        if (assessment == null) {
            return List.of();
        }

        List<RiskRemediationStepResponse> steps = new ArrayList<>();
        for (RiskRecourseSearch.Recourse found : cached(assessment)) {
            // One step per movable reading, so the key is the reading. Two steps against
            // the same feature would be two claims on one action.
            String stepKey = found.feature();
            Optional<SprintRiskAdjustment> standing = ledger.standing(
                    sourceKey(riskType, stepKey), assessment.getCreatedAt());

            steps.add(new RiskRemediationStepResponse(
                    riskType, found.feature(), found.label(),
                    stepKey, found.title(), found.detail(),
                    found.points().setScale(0, RoundingMode.HALF_UP).max(BigDecimal.ONE),
                    found.currentValue(), found.targetValue(),
                    found.baselineScore(), found.projectedScore(),
                    RiskStepGuidance.forFeature(found.feature()),
                    standing.isPresent(),
                    standing.map(SprintRiskAdjustment::getCreatedAt).orElse(null)));
        }
        return steps;
    }

    /**
     * The search, run once per assessment.
     *
     * <p>A counterfactual is a pure function of the assessment it probes around, and an
     * assessment row never changes after it is written — so a sprint's steps can only
     * differ from the last time they were asked for if somebody pressed <em>Assess</em>,
     * which writes a new row and a new key. The steps endpoint is hit on every view of
     * the risk tab, and each miss costs one inference call per rung per lever; against
     * the HTTP model service that is the difference between a page that loads and one
     * that visibly waits.</p>
     *
     * <p>Deliberately in-process rather than the shared Redis cache the rest of the
     * module uses. Redis is configured with default typing for non-final types, and
     * these are records — the list would come back as maps. A bounded map holding a pure
     * function of immutable rows is the smaller thing to get right.</p>
     */
    private List<RiskRecourseSearch.Recourse> cached(RiskAssessment assessment) {
        UUID key = assessment.getId();
        if (key == null) {
            return recourse.search(assessment);
        }
        synchronized (cache) {
            List<RiskRecourseSearch.Recourse> hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        // Outside the lock: the search makes network calls against the model service, and
        // holding a monitor across them would serialise every viewer of every sprint.
        List<RiskRecourseSearch.Recourse> found = recourse.search(assessment);
        synchronized (cache) {
            cache.put(key, found);
        }
        return found;
    }

    /** Enough for every sprint anyone is looking at; the oldest entry falls out. */
    private static final int CACHE_LIMIT = 512;

    /**
     * Per-instance rather than static.
     *
     * <p>The bean is a singleton, so this behaves identically in a running application —
     * and it means the cache cannot outlive the object that owns it, which is what makes
     * the service testable and stops one test's findings reaching the next.</p>
     */
    private final Map<UUID, List<RiskRecourseSearch.Recourse>> cache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<UUID, List<RiskRecourseSearch.Recourse>> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private static String sourceKey(RiskType riskType, String stepKey) {
        return SOURCE_PREFIX + ":" + riskType.name() + ":" + stepKey;
    }
}
