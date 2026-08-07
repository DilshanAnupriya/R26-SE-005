package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.modules.settings.application.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The admin view of the model registry: what is deployed, whether it is switched on,
 * and whether anyone is agreeing with it.
 *
 * <p>Usage is reported next to configuration on purpose. The decision an administrator
 * is actually making on this screen is "should this stay enabled", and a toggle with no
 * acceptance rate beside it turns that into a guess.</p>
 */
@Service
@RequiredArgsConstructor
public class AiModelAdminService {

    /** Window for the usage figures. Long enough to cover a couple of sprints. */
    private static final int USAGE_WINDOW_DAYS = 30;

    private final AiModelRegistry registry;
    private final AiPredictionRepository predictions;
    private final AiFeedbackRepository feedback;
    private final FeatureFlagService featureFlags;

    @Transactional(readOnly = true)
    public List<AiModelResponse> list(UUID workspaceId) {
        Instant since = Instant.now().minus(USAGE_WINDOW_DAYS, ChronoUnit.DAYS);
        List<AiModelResponse> responses = new ArrayList<>();
        for (AiModel model : registry.all()) {
            responses.add(toResponse(model, workspaceId, since));
        }
        return responses;
    }

    @Transactional(readOnly = true)
    public AiModelResponse get(AiModelCode code, UUID workspaceId) {
        return toResponse(registry.require(code), workspaceId,
                Instant.now().minus(USAGE_WINDOW_DAYS, ChronoUnit.DAYS));
    }

    @Transactional
    public AiModelResponse update(AiModelCode code, UpdateAiModelRequest request, UUID workspaceId) {
        AiModel model = registry.update(code, request.enabled(), request.version(),
                request.endpointUrl(), request.latencyBudgetMs());
        return toResponse(model, workspaceId, Instant.now().minus(USAGE_WINDOW_DAYS, ChronoUnit.DAYS));
    }

    // ------------------------------------------------------------------ internals

    private AiModelResponse toResponse(AiModel model, UUID workspaceId, Instant since) {
        return new AiModelResponse(model.getCode(), model.getName(), model.getDescription(),
                model.getVersion(), model.getTaskType(), model.getDefaultMode(), model.isEnabled(),
                model.getLatencyBudgetMs(), model.getEndpointUrl(), model.getCode().getFlagKey(),
                featureFlags.isEnabled(workspaceId, model.getCode().getFlagKey()),
                usage(model.getCode(), since));
    }

    private ModelUsageResponse usage(AiModelCode code, Instant since) {
        long total = predictions.countByModelCodeAndCreatedAtAfter(code, since);
        long failures = predictions.countByModelCodeAndStatusAndCreatedAtAfter(
                code, PredictionStatus.FAILED, since)
                + predictions.countByModelCodeAndStatusAndCreatedAtAfter(
                code, PredictionStatus.TIMEOUT, since);

        long accepted = 0;
        long overridden = 0;
        long dismissed = 0;
        BigDecimal errorSum = BigDecimal.ZERO;
        long errorSamples = 0;

        for (AiFeedbackRepository.FeedbackTally tally : feedback.tallyByAction(code, since)) {
            switch (tally.getAction()) {
                case ACCEPTED -> accepted = tally.getTotal();
                case OVERRIDDEN -> {
                    overridden = tally.getTotal();
                    if (tally.getMeanAbsoluteDelta() != null) {
                        // Only overrides carry a meaningful error: an accepted prediction
                        // has a delta of zero by definition, and averaging those in would
                        // make every model look accurate in proportion to how often it was
                        // waved through.
                        errorSum = BigDecimal.valueOf(tally.getMeanAbsoluteDelta());
                        errorSamples = tally.getTotal();
                    }
                }
                case DISMISSED -> dismissed = tally.getTotal();
            }
        }

        long verdicts = accepted + overridden + dismissed;
        BigDecimal acceptanceRate = verdicts == 0
                ? null
                : BigDecimal.valueOf(accepted * 100L).divide(BigDecimal.valueOf(verdicts), 1,
                RoundingMode.HALF_UP);
        BigDecimal meanAbsoluteError = errorSamples == 0
                ? null
                : errorSum.setScale(3, RoundingMode.HALF_UP);

        return new ModelUsageResponse(total, failures, accepted, overridden, dismissed,
                acceptanceRate, meanAbsoluteError);
    }
}
