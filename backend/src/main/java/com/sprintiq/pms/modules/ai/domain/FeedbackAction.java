package com.sprintiq.pms.modules.ai.domain;

/**
 * What the human did with a suggestion. This is the label column of the retraining
 * set — the product's only source of ground truth about whether a model is useful.
 *
 * <p>{@code DISMISSED} is kept distinct from {@code OVERRIDDEN} on purpose: overriding
 * means "wrong number, here is the right one", dismissing means "not a question I want
 * answered here". They call for opposite responses — retrain the first, remove the
 * panel for the second — and merging them hides that.</p>
 */
public enum FeedbackAction {

    ACCEPTED,
    OVERRIDDEN,
    DISMISSED
}
