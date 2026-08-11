package com.sprintiq.pms.modules.ai.domain;

import java.math.BigDecimal;

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
 */
public record PredictionDriver(String feature,
                               String label,
                               BigDecimal contribution,
                               String direction,
                               String detail) {

    public static final String INCREASE = "INCREASE";
    public static final String DECREASE = "DECREASE";

    public static PredictionDriver up(String feature, String label, BigDecimal contribution, String detail) {
        return new PredictionDriver(feature, label, contribution, INCREASE, detail);
    }

    public static PredictionDriver down(String feature, String label, BigDecimal contribution, String detail) {
        return new PredictionDriver(feature, label, contribution.abs().negate(), DECREASE, detail);
    }
}
