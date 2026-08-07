package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.AiModel;
import com.sprintiq.pms.modules.ai.domain.AiPrediction;
import com.sprintiq.pms.modules.ai.domain.AiPredictionRepository;
import com.sprintiq.pms.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes prediction rows, and counts every inference whether or not the row survives.
 *
 * <p>The row joins the caller's transaction rather than opening its own. Predictions are
 * product data — users read their history, attach feedback to them, and follow them back
 * to the story they describe — so a prediction that outlived the transaction which
 * created that story would be a dangling record pointing at nothing. Its {@code project_id}
 * is a foreign key; committing independently would fail outright on a rolled-back
 * project. And the allocation ranking scores every candidate in one request, so an
 * independent transaction per call would take a second connection from the pool per
 * developer on the team.</p>
 *
 * <p>The concern that motivated an independent transaction — a model call really happened,
 * really cost latency and possibly money, and should be visible even if the surrounding
 * work is abandoned — is served by the meters instead. Counters do not roll back, they
 * are what an operator watches anyway, and they cost nothing per call.</p>
 */
@Service
@RequiredArgsConstructor
public class PredictionRecorder {

    private static final String INFERENCE_COUNTER = "sprintiq.ai.inference";
    private static final String LATENCY_TIMER = "sprintiq.ai.inference.latency";

    private final AiPredictionRepository repository;
    private final MeterRegistry meterRegistry;

    @Transactional
    public AiPrediction record(AiInferenceService.InferenceCommand command,
                               AiModel model,
                               AiModelClient.InferenceResult result,
                               String featureHash,
                               int latencyMs,
                               boolean cacheHit) {
        meter(model, result, latencyMs, cacheHit);

        AiPrediction prediction = AiPrediction.builder()
                .projectId(command.projectId())
                .modelCode(model.getCode())
                .modelVersion(model.getVersion())
                .mode(model.getDefaultMode())
                .entityType(command.entityType())
                .entityId(command.entityId())
                .requestPayload(command.features().asMap())
                .predictedValue(result.value())
                .predictedLabel(result.label())
                .confidence(result.confidence())
                .lowerBound(result.lowerBound())
                .upperBound(result.upperBound())
                .drivers(result.drivers())
                .explanation(truncate(result.explanation(), 1000))
                .rawResponse(result.raw())
                .status(result.status())
                .errorMessage(truncate(result.errorMessage(), 500))
                .latencyMs(latencyMs)
                .cacheHit(cacheHit)
                .featureHash(featureHash)
                .createdBy(TenantContext.userId().orElse(null))
                .build();
        prediction.setOrganizationId(TenantContext.requireOrganizationId());
        return repository.save(prediction);
    }

    /**
     * Counters and a latency timer per model.
     *
     * <p>Cache hits are counted separately rather than folded in: a rising hit rate is
     * good news about cost and terrible news about freshness if it turns out the feature
     * hash stopped changing, and one blended number cannot say which.</p>
     */
    private void meter(AiModel model, AiModelClient.InferenceResult result, int latencyMs,
                       boolean cacheHit) {
        meterRegistry.counter(INFERENCE_COUNTER,
                "model", model.getCode().name(),
                "version", model.getVersion(),
                "status", result.status().name(),
                "cache", Boolean.toString(cacheHit)).increment();

        if (!cacheHit) {
            meterRegistry.timer(LATENCY_TIMER, "model", model.getCode().name())
                    .record(java.time.Duration.ofMillis(latencyMs));
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
