package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.settings.application.FeatureFlagService;
import com.sprintiq.pms.security.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The single path every prediction takes: gate, cache, call, record.
 *
 * <p>The four model services do feature assembly and interpretation; none of them talk
 * to the client directly. Putting the flag check, the cache, the timeout and the write
 * in one place is what makes the guarantees in the API map true for every endpoint
 * rather than for whichever ones remembered — every prediction is persisted, every
 * failure is persisted, and no capability can be called while its flag is off.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiInferenceService {

    private final AiModelClient client;
    private final AiModelRegistry registry;
    private final AiPredictionRepository predictionRepository;
    private final PredictionRecorder recorder;
    private final FeatureFlagService featureFlags;
    private final AppProperties properties;

    /** What a caller asks for. The features are already assembled. */
    public record InferenceCommand(AiModelCode modelCode,
                                   EntityType entityType,
                                   UUID entityId,
                                   UUID projectId,
                                   UUID workspaceId,
                                   FeatureVector features) {}

    /**
     * Runs one inference and returns the persisted prediction.
     *
     * <p>The row joins the caller's transaction — see {@link PredictionRecorder} for why —
     * while the meters it also writes do not, so a call that happened is still counted
     * even if the surrounding work is abandoned.</p>
     */
    public AiPrediction infer(InferenceCommand command) {
        requireCapability(command.modelCode(), command.workspaceId());
        AiModel model = registry.requireEnabled(command.modelCode());

        String featureHash = FeatureHasher.hash(model.getCode().name(), model.getVersion(),
                command.features().asMap());

        AiPrediction cached = findCached(model, featureHash);
        if (cached != null) {
            return recordCacheHit(command, model, cached, featureHash);
        }

        Duration budget = registry.latencyBudget(model)
                .compareTo(Duration.ofMillis(properties.getAi().getTimeoutMs())) < 0
                ? registry.latencyBudget(model)
                : Duration.ofMillis(properties.getAi().getTimeoutMs());

        long startedAt = System.nanoTime();
        AiModelClient.InferenceResult result;
        try {
            result = client.infer(new AiModelClient.InferenceRequest(
                    model.getCode(), model.getVersion(), model.getDefaultMode(),
                    command.features(), budget));
        } catch (RuntimeException ex) {
            // A client that throws instead of returning a failure result is a bug in the
            // client, but the caller must still get a persisted row rather than a 500.
            log.error("AI client {} threw for {}", client.providerName(), model.getCode(), ex);
            result = AiModelClient.InferenceResult.failure(PredictionStatus.FAILED, ex.getMessage());
        }
        int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);

        if (result.status() == PredictionStatus.SUCCESS && latencyMs > model.getLatencyBudgetMs()) {
            // Answered, but too late to be trusted as fresh. Recorded as degraded so the
            // pattern is visible in the model's own statistics.
            result = new AiModelClient.InferenceResult(result.value(), result.label(), result.confidence(),
                    result.lowerBound(), result.upperBound(), result.drivers(), result.explanation(),
                    result.raw(), PredictionStatus.DEGRADED,
                    "Exceeded the %dms latency budget".formatted(model.getLatencyBudgetMs()));
        }

        return recorder.record(command, model, result, featureHash, latencyMs, false);
    }

    /**
     * Fails loudly when a capability is switched off, rather than returning an empty
     * result the UI would render as "no suggestion available".
     */
    public void requireCapability(AiModelCode modelCode, UUID workspaceId) {
        if (!properties.getAi().isEnabled()) {
            throw new ApplicationException(ErrorCode.AI_FEATURE_DISABLED,
                    "AI features are disabled on this deployment");
        }
        if (!featureFlags.isEnabled(workspaceId, modelCode.getFlagKey())) {
            throw new ApplicationException(ErrorCode.AI_FEATURE_DISABLED,
                    "%s is switched off for this workspace".formatted(modelCode.getDisplayName()));
        }
    }

    /**
     * The result a panel should show, or empty when the model could not answer.
     * Callers use this to degrade to manual input instead of surfacing an error.
     */
    public AiPrediction inferOrNull(InferenceCommand command) {
        AiPrediction prediction = infer(command);
        return prediction.isUsable() ? prediction : null;
    }

    @Transactional(readOnly = true)
    public AiPrediction latestFor(AiModelCode modelCode, EntityType entityType, UUID entityId) {
        return predictionRepository
                .findFirstByModelCodeAndEntityTypeAndEntityIdOrderByCreatedAtDesc(modelCode, entityType, entityId)
                .orElse(null);
    }

    // ------------------------------------------------------------------ internals

    private AiPrediction findCached(AiModel model, String featureHash) {
        if (featureHash == null) {
            return null;
        }
        Instant notBefore = Instant.now().minus(properties.getAi().getCacheTtl());
        List<AiPrediction> matches = predictionRepository.findCacheable(
                model.getCode(), featureHash, notBefore, PageRequest.of(0, 1));
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Cache hits are written as their own rows with {@code cacheHit = true}.
     *
     * <p>It would be cheaper to return the original row untouched, but then the
     * prediction table would say a model was consulted five times when it was consulted
     * once — and the feedback the user leaves has to attach to <em>their</em> view of
     * the suggestion, not to a stranger's request from four hours earlier.</p>
     */
    private AiPrediction recordCacheHit(InferenceCommand command, AiModel model,
                                        AiPrediction source, String featureHash) {
        AiModelClient.InferenceResult replay = new AiModelClient.InferenceResult(
                source.getPredictedValue(), source.getPredictedLabel(), source.getConfidence(),
                source.getLowerBound(), source.getUpperBound(), source.getDrivers(),
                source.getExplanation(), source.getRawResponse(), source.getStatus(), null);
        return recorder.record(command, model, replay, featureHash, 0, true);
    }
}
