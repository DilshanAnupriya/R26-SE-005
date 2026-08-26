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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The fix half of the risk screen: what to do about each driver, and what doing it is worth.
 *
 * <p>Nothing here decides what the steps are. {@link RiskRecourseSearch} derives them by
 * re-running the trained model with one reading moved at a time and measuring the fall in
 * the prediction; this service is the part that remembers which of them a team has
 * claimed, and turns a claim into a number on the gauge.</p>
 *
 * <p><strong>Ticking a step is a claim, and the claim stands until the model can see
 * it.</strong> Completion is recorded as a negative entry in the same ledger that charges
 * a sprint for a blocked task, so the gauge moves immediately and the reason is on the
 * audit trail next to everything else that moved it. Re-scoring does not cancel that
 * entry. It settles it — {@link #settleAfterScoring} re-prices the claim against the new
 * assessment, and withdraws it once the model stops offering the step at all, which is
 * what a reading that has actually moved looks like from here.</p>
 *
 * <p>The distinction matters because the two cases are opposite. Where the work was done,
 * the improvement is now in the model's own figure and the claim retires into it, so
 * nothing is counted twice. Where it was not, the board still reads the way it did, the
 * step is still on offer, and the points stay off — rather than reappearing the moment
 * somebody presses <em>Assess</em>, which is what the earlier expire-on-scoring rule did
 * and what taught people the checkbox did not hold.</p>
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

    /** How a claim reads on the audit trail, and how its title is recovered from it. */
    private static final String CLAIM_REASON_PREFIX = "Fix applied: ";

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
            adjustmentService.restateStanding(sprintId, riskType, sourceKey,
                    step.points().negate(), CLAIM_REASON_PREFIX + step.title());
        } else {
            adjustmentService.clear(sprintId, sourceKey);
        }
        return stepsFor(sprintId);
    }

    /**
     * Settles the claimed steps against a scoring that has just been written.
     *
     * <p>Called by {@link RiskService} immediately after it saves new assessment rows,
     * and it is what makes a tick mean something durable. A claimed step is re-priced to
     * what the fresh scoring says it is worth, and withdrawn entirely once the model
     * stops offering it — which is what happens when the reading has actually moved, so
     * the credit is retired at precisely the point the score contains it on its own.</p>
     *
     * <p>The alternative, which this replaces, was to let re-scoring expire the claim.
     * That was right in the case where the work had been done and wrong in every other:
     * a team that ticked a step and then pressed <em>Assess</em> watched the points they
     * had just taken off come straight back, because nothing the model reads had changed
     * yet. The score bounced, and a control whose effect the next click undoes is one
     * people stop using.</p>
     *
     * <p><strong>A claim is only ever released on positive evidence.</strong> The
     * question asked of each one is whether the new assessment still names its reading as
     * something raising the score, and that is read off the stored driver list — data on
     * the row this scoring just wrote, not a probe. It deliberately is not "is a step
     * still on offer": the recourse search drops a lever whose probe times out, so a slow
     * model service would have cancelled the team's credit and bounced the gauge back up,
     * which is the very failure this method exists to end.</p>
     *
     * <p>Re-pricing is the part that may be skipped. Where the search did answer, the
     * claim is revalued to what this scoring says the step is now worth; where it could
     * not, the claim stands at what it was worth before. Neither outcome hands points
     * back.</p>
     */
    @Transactional
    public void settleAfterScoring(UUID sprintId) {
        for (RiskType riskType : RiskType.values()) {
            settleAfterScoring(sprintId, riskType);
        }
    }

    /** As {@link #settleAfterScoring(UUID)}, for the one type a single-model re-score wrote. */
    @Transactional
    public void settleAfterScoring(UUID sprintId, RiskType riskType) {
        SprintRiskAdjustmentService.Ledger ledger = adjustmentService.ledgerFor(sprintId);
        List<SprintRiskAdjustment> claimed = claimsFor(ledger, riskType);
        if (claimed.isEmpty()) {
            // Nothing claimed, so nothing to settle - and, more usefully, no reason to
            // run the recourse search. An ordinary assessment pays nothing for this.
            return;
        }

        RiskAssessment assessment = assessments
                .findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(sprintId, riskType)
                .orElse(null);
        if (assessment == null) {
            return;
        }

        Map<String, RiskRemediationStepResponse> measured = new LinkedHashMap<>();
        for (RiskRemediationStepResponse step : stepsFor(sprintId, riskType, ledger)) {
            // Only the ones this scoring actually priced. The list also carries claims
            // rebuilt from the ledger for steps the search could not reach, and those
            // already hold the value being asked about — re-pricing one to itself would
            // put a write on the audit trail saying nothing happened.
            if (step.baselineScore() != null) {
                measured.put(step.stepKey(), step);
            }
        }

        for (SprintRiskAdjustment entry : claimed) {
            String feature = stepKeyOf(entry.getSourceKey());
            if (!stillRaising(assessment, feature)) {
                // The model has stopped naming this reading as something pushing the
                // score up, which is what a finished step looks like from here: the
                // improvement is in the new figure, and going on paying for it would
                // take the same points off twice.
                adjustmentService.clear(sprintId, entry.getSourceKey());
                continue;
            }
            RiskRemediationStepResponse step = measured.get(feature);
            if (step != null) {
                // Still raising, so the board has not shown the change yet and the claim
                // stands. Revalued to this scoring own figure, which is also the number
                // the panel is about to show beside the tick.
                adjustmentService.reprice(sprintId, entry.getSourceKey(),
                        step.points().negate(), CLAIM_REASON_PREFIX + step.title());
            }
            // No step in hand means the probe for it did not answer in time. The claim is
            // left exactly as it was rather than guessed at: a claim that quietly changed
            // value because the model service was busy would be worse than a stale one.
        }
    }

    /**
     * Whether this scoring still blames the reading a claim was made against.
     *
     * <p>Read off the assessment own stored drivers, and matched to the same test
     * {@link RiskRecourseSearch} uses to decide what is worth probing at all — so
     * "no longer raising" and "no longer offered as a step" mean the same thing, minus
     * the network call that can fail.</p>
     */
    private static boolean stillRaising(RiskAssessment assessment, String feature) {
        if (assessment.getDrivers() == null) {
            return false;
        }
        return assessment.getDrivers().stream()
                .filter(driver -> feature.equals(driver.feature()))
                .anyMatch(driver -> PredictionDriver.INCREASE.equals(driver.direction())
                                    && driver.contribution() != null
                                    && driver.contribution().signum() != 0);
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
        Set<String> priced = new LinkedHashSet<>();
        for (RiskRecourseSearch.Recourse found : cached(assessment)) {
            // One step per movable reading, so the key is the reading. Two steps against
            // the same feature would be two claims on one action.
            String stepKey = found.feature();
            priced.add(stepKey);
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

        // A claim the search did not price this time is still a claim. The tick and the
        // credit are one ledger row, so dropping the row from this list would leave the
        // gauge quietly paying for a step with no checkbox on screen - nothing to see it
        // by and nothing to untick. That is what a model service being down used to do to
        // this panel: every probe fails, the search returns nothing, and the whole
        // checklist vanished along with the team's ticks.
        for (SprintRiskAdjustment claim : claimsFor(ledger, riskType)) {
            String stepKey = stepKeyOf(claim.getSourceKey());
            if (!stepKey.isEmpty() && priced.add(stepKey)) {
                steps.add(claimed(riskType, stepKey, claim));
            }
        }
        return steps;
    }

    /**
     * A claimed step the model cannot currently re-price, rebuilt from the ledger.
     *
     * <p>Everything here comes from the row that <em>is</em> the claim, so it stays
     * truthful without the model: the title is the sentence the step was claimed under
     * and the points are what it is actually holding off right now. The measurement
     * fields are left null rather than guessed — the panel drops the "53% to 35%" note
     * when they are absent, which is correct, because no scoring has measured this one
     * since.</p>
     */
    private RiskRemediationStepResponse claimed(RiskType riskType, String stepKey,
                                                SprintRiskAdjustment claim) {
        String feature = featureOf(stepKey);
        return new RiskRemediationStepResponse(
                riskType, feature, feature, stepKey, titleOf(claim),
                "Claimed against an earlier scoring. The model has not been able to "
                + "re-price this step since, so it is shown at what it is still worth.",
                claim.getDelta().abs().setScale(0, RoundingMode.HALF_UP).max(BigDecimal.ONE),
                null, null, null, null,
                RiskStepGuidance.forFeature(feature),
                true, claim.getCreatedAt());
    }

    /** Every remediation claim standing against one risk on this sprint. */
    private static List<SprintRiskAdjustment> claimsFor(SprintRiskAdjustmentService.Ledger ledger,
                                                        RiskType riskType) {
        return ledger.entries().stream()
                .filter(entry -> entry.getRiskType() == riskType)
                .filter(entry -> entry.getSourceKey() != null
                                 && entry.getSourceKey().startsWith(SOURCE_PREFIX + ":"))
                .toList();
    }

    /** The sentence a claim was made under, recovered from its ledger reason. */
    private static String titleOf(SprintRiskAdjustment claim) {
        String reason = claim.getReason() == null ? "" : claim.getReason();
        return reason.startsWith(CLAIM_REASON_PREFIX)
                ? reason.substring(CLAIM_REASON_PREFIX.length()) : reason;
    }

    /**
     * The reading a step key is about.
     *
     * <p>Today a step key is the feature and nothing else, but rows written under an
     * older {@code <feature>:<step>} scheme are still in the ledger, and the guidance
     * lookup wants the feature half of those too.</p>
     */
    private static String featureOf(String stepKey) {
        int separator = stepKey.indexOf(':');
        return separator < 0 ? stepKey : stepKey.substring(0, separator);
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

    /**
     * The step a ledger key names.
     *
     * <p>Split on the first two separators only. A feature name has never contained a
     * colon, but reconstructing the tail rather than taking the third field means a
     * lever that one day does cannot silently stop matching its own claim.</p>
     */
    private static String stepKeyOf(String sourceKey) {
        String[] parts = sourceKey.split(":", 3);
        return parts.length == 3 ? parts[2] : "";
    }
}
