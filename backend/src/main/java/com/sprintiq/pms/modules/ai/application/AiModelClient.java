package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.InferenceMode;
import com.sprintiq.pms.modules.ai.domain.PredictionDriver;
import com.sprintiq.pms.modules.ai.domain.PredictionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The boundary between this product and whatever is actually doing the inference.
 *
 * <p>Everything above this interface — feature assembly, persistence, explanations,
 * Accept/Override/Compare, the audit trail — is finished and testable before a single
 * line of Python exists. That ordering is intentional: the AI features are the product's
 * differentiator, and having them blocked on a model service that is not ready yet
 * would mean shipping a project management tool with four empty panels.</p>
 *
 * <p>Two implementations ship: a deterministic heuristic one for development, demos and
 * tests, and an HTTP one for the real services. The switch is
 * {@code app.ai.provider=mock|http}.</p>
 */
public interface AiModelClient {

    /** Identifies the implementation in logs and in the prediction's raw response. */
    String providerName();

    InferenceResult infer(InferenceRequest request);

    /**
     * @param features exactly what gets persisted as the prediction's request payload
     * @param timeout  the model's latency budget; past it the caller degrades rather
     *                 than blocks
     */
    record InferenceRequest(AiModelCode modelCode,
                            String modelVersion,
                            InferenceMode mode,
                            FeatureVector features,
                            Duration timeout) {}

    /**
     * @param value       regression output, null for pure classifiers
     * @param label       classification output, null for pure regressors
     * @param confidence  0..1
     * @param drivers     ranked contributions; never empty for a successful result
     * @param explanation one plain sentence a non-technical stakeholder can read
     */
    record InferenceResult(BigDecimal value,
                           String label,
                           BigDecimal confidence,
                           BigDecimal lowerBound,
                           BigDecimal upperBound,
                           List<PredictionDriver> drivers,
                           String explanation,
                           Map<String, Object> raw,
                           PredictionStatus status,
                           String errorMessage) {

        public static InferenceResult failure(PredictionStatus status, String message) {
            return new InferenceResult(null, null, null, null, null, List.of(), null,
                    Map.of(), status, message);
        }

        public boolean isUsable() {
            return status.isUsable();
        }
    }
}
