package com.sprintiq.pms.modules.ai.domain;

/**
 * How a model was invoked. Recorded on every prediction because accuracy is only
 * comparable within a mode — a fine-tuned model's error distribution says nothing
 * about the zero-shot one that answered the same question last month.
 */
public enum InferenceMode {

    ZERO_SHOT,
    FEW_SHOT,
    FINE_TUNED
}
