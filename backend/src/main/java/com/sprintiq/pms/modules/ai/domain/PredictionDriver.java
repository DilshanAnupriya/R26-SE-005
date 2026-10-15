package com.sprintiq.pms.modules.ai.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One ranked reason behind a prediction: which feature moved the answer, by how much,
 * and in which direction.
 *
 * <p>Every model in this product returns these, and every panel shows them. A number
 * with no reasons attached is something a planning meeting either believes blindly or
 * ignores entirely — both are worse than a number the team can argue with. "Estimated
 * 8 points, mostly because this story touches 3 components and its acceptance criteria
 * mention two integrations" is a claim a developer can correct.</p>
 *
 * @param feature      machine name of the driver, e.g. {@code acceptance_criteria_count}
 * @param label        human-readable name shown in the UI
 * @param contribution magnitude in the prediction's own units, signed
 * @param direction    {@code INCREASE} or {@code DECREASE}
 * @param detail       optional one-line evidence, e.g. "4 criteria vs a median of 2"
 * @param fix          optional remedy the model measured for a driver raising the score —
 *                     today only the over-budget model's, read off its own SHAP values:
 *                     the reading to move, the target, and the probability the model
 *                     returned there. Passed through as the model sent it, so a field the
 *                     service adds reaches the panel without a change here; null for
 *                     every other model, and for drivers stored before fixes existed.
 */
public record PredictionDriver(String feature,
                               String label,
                               BigDecimal contribution,
                               String direction,
                               String detail,
                               Map<String, Object> fix) {

    public static final String INCREASE = "INCREASE";
    public static final String DECREASE = "DECREASE";

    /** A driver with no measured fix, which is what every model but over-budget sends. */
    public PredictionDriver(String feature, String label, BigDecimal contribution,
                            String direction, String detail) {
        this(feature, label, contribution, direction, detail, null);
    }

    public static PredictionDriver up(String feature, String label, BigDecimal contribution, String detail) {
        return new PredictionDriver(feature, label, contribution, INCREASE, detail);
    }

    public static PredictionDriver down(String feature, String label, BigDecimal contribution, String detail) {
        return new PredictionDriver(feature, label, contribution.abs().negate(), DECREASE, detail);
    }
}
