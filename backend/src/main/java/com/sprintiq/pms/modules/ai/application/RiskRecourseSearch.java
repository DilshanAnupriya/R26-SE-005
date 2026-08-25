package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.application.RiskRecourseLevers.Action;
import com.sprintiq.pms.modules.ai.application.RiskRecourseLevers.Lever;
import com.sprintiq.pms.modules.ai.domain.AiPrediction;
import com.sprintiq.pms.modules.ai.domain.AiPredictionRepository;
import com.sprintiq.pms.modules.ai.domain.PredictionDriver;
import com.sprintiq.pms.modules.ai.domain.RiskAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * What would actually lower this score, found by asking the model.
 *
 * <p>Counterfactual recourse rather than stored advice. For each reading the sprint is
 * allowed to move ({@link RiskRecourseLevers}), this re-runs the same model on the same
 * feature vector with that one coordinate changed, and reads the new probability back.
 * The action, the target value and what it is worth are therefore all measurements of
 * the trained model's behaviour on this sprint, not text somebody wrote in advance.</p>
 *
 * <p>Two properties of that are worth stating plainly, because both were things the
 * previous hand-written catalogue could only assert:</p>
 *
 * <ul>
 *   <li><strong>The relief is measured, not agreed.</strong> A step is worth
 *       {@code (score now − score with the change) × 100} ledger points. The old
 *       catalogue apportioned a fixed half of the score by a hand-set weight per step;
 *       this number comes out of the model.</li>
 *   <li><strong>The model can decline.</strong> Where moving a lever does not lower the
 *       prediction — which happens, because gradient-boosted trees are not monotone in
 *       their inputs — no step is offered for it. Nothing here can propose an action
 *       the model does not actually reward.</li>
 * </ul>
 *
 * <p>The search is coordinate-wise and bounded: each lever is probed alone, over a short
 * ladder of plausible targets, and the best is kept. Joint search over several features
 * would find larger reductions and is what the recourse literature does when the cost
 * function is known — here it would also multiply the number of inference calls by the
 * size of the power set, to recommend a simultaneous change nobody could act on as one
 * decision.</p>
 *
 * <p>Probes call the model client directly rather than going through
 * {@link AiInferenceService}. Each probe is a hypothetical, and recording twenty of them
 * per page view would fill the prediction audit trail with sprints that never existed.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskRecourseSearch {

    private final AiModelClient client;
    private final AiPredictionRepository predictions;

    /** The ledger's scale: 100 points to a whole probability. */
    private static final BigDecimal POINTS_PER_UNIT = BigDecimal.valueOf(100);

    /**
     * Per-probe budget.
     *
     * <p>Short on purpose. A lever whose probe times out is dropped rather than waited
     * on — the panel is better off offering three measured steps than blocking a page
     * load on a fourth.</p>
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** How far along the way to the bound each lever is tried. */
    private static final double[] LADDER = {0.35, 0.7, 1.0};

    /** Below this the model is saying the lever does not help, and no step is offered. */
    private static final BigDecimal MEANINGFUL_POINTS = BigDecimal.ONE;

    /**
     * One measured counterfactual: change this reading to that value, and the model
     * scores the sprint this much lower.
     *
     * @param baselineScore  the model's probability on the sprint as it stands
     * @param projectedScore its probability with only this coordinate moved
     * @param points         the fall between them, on the ledger's 0–100 scale
     */
    public record Recourse(String feature,
                           String label,
                           Action action,
                           String noun,
                           String unit,
                           String currentValue,
                           String targetValue,
                           BigDecimal baselineScore,
                           BigDecimal projectedScore,
                           BigDecimal points) {

        /** The step, as a sentence built from the search result. */
        public String title() {
            // A percentage carries its own sign; anything else is a word after a space.
            String amount = unit.isBlank() || "%".equals(unit) ? targetValue
                    : targetValue + " " + unit;
            return switch (action) {
                case REDUCE -> "Reduce %s from %s to %s".formatted(noun, currentValue, amount);
                case RAISE -> "Raise %s from %s to %s".formatted(noun, currentValue, amount);
                case ENABLE -> "Put %s in place".formatted(noun);
            };
        }

        /** What the model says the change buys, in the model's own numbers. */
        public String detail() {
            return "The model scores this sprint at %s now, and %s with %s at %s — a measured "
                   .formatted(percent(baselineScore), percent(projectedScore), noun, targetValue)
                   + points.setScale(0, RoundingMode.HALF_UP) + "-point fall.";
        }

        private static String percent(BigDecimal score) {
            return score.multiply(POINTS_PER_UNIT).setScale(0, RoundingMode.HALF_UP) + "%";
        }
    }

    /**
     * Every measured step for one assessment, best first.
     *
     * <p>Returns nothing rather than throwing when the model cannot be probed — a
     * missing prediction row, a client that is down. The scores on screen are unaffected
     * by there being no checklist under them, and failing the risk panel because a
     * hypothetical could not be evaluated would make the optional half load-bearing.</p>
     */
    public List<Recourse> search(RiskAssessment assessment) {
        AiPrediction prediction = assessment.getPredictionId() == null ? null
                : predictions.findById(assessment.getPredictionId()).orElse(null);
        if (prediction == null || prediction.getRequestPayload() == null
                || prediction.getRequestPayload().isEmpty()) {
            // Assessments written before the features were recorded, or by a path that
            // did not persist them. Honest silence beats inventing a checklist.
            return List.of();
        }

        Map<String, Object> features = new LinkedHashMap<>(prediction.getRequestPayload());
        BigDecimal baseline = probe(prediction, features);
        if (baseline == null) {
            return List.of();
        }

        List<Recourse> found = new ArrayList<>();
        for (PredictionDriver driver : raisingDrivers(assessment)) {
            Lever lever = RiskRecourseLevers.forFeature(driver.feature());
            if (lever == null) {
                continue;
            }
            Recourse best = best(prediction, features, baseline, lever, driver);
            if (best != null) {
                found.add(best);
            }
        }
        found.sort(Collections.reverseOrder(java.util.Comparator.comparing(Recourse::points)));
        return List.copyOf(found);
    }

    // ---------------------------------------------------------------- the search

    /**
     * The drivers worth probing: the ones raising this score.
     *
     * <p>Scoped to the drivers rather than sweeping every lever because a reading the
     * model did not rank is one it did not find notable on this sprint, and probing it
     * would spend inference calls to offer an action nothing asked for.</p>
     */
    private List<PredictionDriver> raisingDrivers(RiskAssessment assessment) {
        if (assessment.getDrivers() == null) {
            return List.of();
        }
        return assessment.getDrivers().stream()
                .filter(driver -> PredictionDriver.INCREASE.equals(driver.direction()))
                .filter(driver -> driver.contribution() != null
                        && driver.contribution().signum() != 0)
                .toList();
    }

    /**
     * The best target on one lever's ladder.
     *
     * <p>Every rung is evaluated rather than stopping at the first that helps, because
     * a boosted-tree model is not monotone in its inputs: a smaller cut can score better
     * than a larger one, and taking the first improvement would sometimes recommend
     * doing more work for less relief.</p>
     */
    private Recourse best(AiPrediction prediction, Map<String, Object> features,
                          BigDecimal baseline, Lever lever, PredictionDriver driver) {
        Object current = features.get(lever.feature());
        Recourse best = null;

        for (Object target : targets(lever, current)) {
            Map<String, Object> probed = new LinkedHashMap<>(features);
            probed.put(lever.feature(), target);

            BigDecimal projected = probe(prediction, probed);
            if (projected == null) {
                continue;
            }
            BigDecimal points = baseline.subtract(projected).multiply(POINTS_PER_UNIT)
                    .setScale(2, RoundingMode.HALF_UP);
            if (points.compareTo(MEANINGFUL_POINTS) < 0) {
                continue;
            }
            if (best == null || points.compareTo(best.points()) > 0) {
                best = new Recourse(lever.feature(),
                        driver.label() == null ? lever.noun() : driver.label(),
                        lever.action(), lever.noun(), lever.unit(),
                        format(current, lever), format(target, lever),
                        baseline, projected, points);
            }
        }
        return best;
    }

    /**
     * The plausible values for one lever, nearest first.
     *
     * <p>A ladder rather than a continuous search: the numbers have to survive being
     * read out in a stand-up, and "cut the commitment to 25.836 points" is not an
     * instruction. Integer readings get integer targets, taken from the current value's
     * own shape rather than from a flag somebody has to remember to set.</p>
     */
    private List<Object> targets(Lever lever, Object current) {
        if (lever.action() == Action.ENABLE) {
            return List.of(Boolean.TRUE);
        }
        double value = toDouble(current);
        if (Double.isNaN(value)) {
            return List.of();
        }

        double destination = lever.action() == Action.REDUCE
                ? value * lever.bound()
                : Math.max(value * lever.bound(), value + 1);
        // A RAISE from zero has nowhere to go by multiplication.
        if (lever.action() == Action.RAISE && value == 0) {
            destination = Math.max(lever.bound(), 1);
        }

        boolean whole = isWhole(current);
        LinkedHashSet<Object> targets = new LinkedHashSet<>();
        for (double rung : LADDER) {
            double candidate = value + (destination - value) * rung;
            candidate = whole ? Math.round(candidate) : round(candidate);
            if (lever.action() == Action.REDUCE && candidate >= value) {
                continue;
            }
            if (lever.action() == Action.RAISE && candidate <= value) {
                continue;
            }
            targets.add(whole ? (Object) (long) candidate : (Object) candidate);
        }
        return List.copyOf(targets);
    }

    /**
     * One hypothetical, scored.
     *
     * <p>Straight to the client, with the model version and mode that produced the
     * assessment — a probe answered by a different version than the baseline would make
     * the difference between them a fact about the deployment rather than about the
     * sprint.</p>
     */
    private BigDecimal probe(AiPrediction prediction, Map<String, Object> features) {
        try {
            AiModelClient.InferenceResult result = client.infer(new AiModelClient.InferenceRequest(
                    prediction.getModelCode(), prediction.getModelVersion(), prediction.getMode(),
                    FeatureVector.create().putAll(features), PROBE_TIMEOUT));
            return result.isUsable() && result.value() != null ? result.value() : null;
        } catch (RuntimeException ex) {
            log.debug("Recourse probe failed for {}: {}",
                    prediction.getModelCode(), ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ numbers

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        try {
            return value == null ? Double.NaN : Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    /** Whether the reading counts things, so its targets should too. */
    private static boolean isWhole(Object value) {
        double number = toDouble(value);
        return !Double.isNaN(number) && number == Math.rint(number);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Numbers as a person would say them: no trailing zeros, no scientific notation. */
    private static String format(Object value, Lever lever) {
        if (value instanceof Boolean flag) {
            return flag ? "yes" : "no";
        }
        double number = toDouble(value);
        if (Double.isNaN(number)) {
            return String.valueOf(value);
        }
        if ("%".equals(lever.unit())) {
            return Math.round(number * 100) + "%";
        }
        return number == Math.rint(number)
                ? String.valueOf((long) number)
                : BigDecimal.valueOf(number).setScale(2, RoundingMode.HALF_UP)
                        .stripTrailingZeros().toPlainString();
    }
}
