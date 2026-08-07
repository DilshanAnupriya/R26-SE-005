package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The Accept / Override / Dismiss loop.
 *
 * <p>This is the smallest piece of the AI layer and the most important one. Without it
 * the product has four models that nobody can tell are working; with it, every panel
 * produces a labelled example every time someone uses it, and "is M1 any good" becomes
 * a query rather than an opinion.</p>
 *
 * <p>Accepting is what writes an AI number through to a story's real estimate. Nothing
 * else does — the model's suggestion sits in the {@code ai*} columns until a person
 * agrees with it, and the audit trail records who agreed.</p>
 */
@Service
@RequiredArgsConstructor
public class AiFeedbackService {

    static final Set<String> SORT_FIELDS = Set.of("createdAt", "modelCode", "confidence", "status");

    private final AiPredictionRepository predictions;
    private final AiFeedbackRepository feedback;
    private final StoryFeatureSource stories;

    /**
     * Records a verdict, and — when asked — applies the resulting number to the item.
     *
     * <p>{@code apply} defaults to true for accept and override because that is what a
     * user pressing "Accept" in a planning session means. It can be turned off for the
     * comparison view, where someone is exploring what the model would have said without
     * wanting to change anything yet.</p>
     */
    @Transactional
    public FeedbackResponse submit(UUID predictionId, SubmitFeedbackRequest request) {
        AiPrediction prediction = predictions.findById(predictionId)
                .orElseThrow(() -> new ResourceNotFoundException("AiPrediction", predictionId));

        if (request.action() == FeedbackAction.OVERRIDDEN && request.finalValue() == null) {
            throw new BusinessRuleException(
                    "An override has to say what the value should be instead");
        }

        BigDecimal predictedValue = prediction.getPredictedValue();
        BigDecimal finalValue = switch (request.action()) {
            case ACCEPTED -> predictedValue;
            case OVERRIDDEN -> request.finalValue();
            case DISMISSED -> null;
        };
        BigDecimal delta = predictedValue != null && finalValue != null
                ? finalValue.subtract(predictedValue)
                : null;

        AiFeedback record = AiFeedback.builder()
                .predictionId(predictionId)
                .action(request.action())
                .predictedValue(predictedValue)
                .finalValue(finalValue)
                .delta(delta)
                .reason(request.reason())
                .userId(TenantContext.requireUserId())
                .build();
        record.setOrganizationId(TenantContext.requireOrganizationId());
        AiFeedback saved = feedback.save(record);

        boolean apply = request.apply() == null
                ? request.action() != FeedbackAction.DISMISSED
                : request.apply();
        if (apply) {
            applyToItem(prediction, request.action(), finalValue);
        }

        return FeedbackResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<PredictionEnvelope> search(AiModelCode modelCode, EntityType entityType,
                                                   UUID entityId, UUID projectId, Instant from,
                                                   Instant to, PageQuery query) {
        return PageResponse.from(
                predictions.search(modelCode, entityType, entityId, projectId, from, to,
                        query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt"))),
                PredictionEnvelope::from);
    }

    @Transactional(readOnly = true)
    public PredictionDetailResponse detail(UUID predictionId) {
        AiPrediction prediction = predictions.findById(predictionId)
                .orElseThrow(() -> new ResourceNotFoundException("AiPrediction", predictionId));
        FeedbackResponse latest = feedback.findFirstByPredictionIdOrderByCreatedAtDesc(predictionId)
                .map(FeedbackResponse::from).orElse(null);
        return new PredictionDetailResponse(PredictionEnvelope.from(prediction),
                prediction.getProjectId(), prediction.getRequestPayload(),
                prediction.getErrorMessage(), latest);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Writes an accepted or overridden value onto the underlying item.
     *
     * <p>Only story point estimation has a target to write to today. Risk, allocation
     * and cost are advisory outputs with no field on the item they would update — a
     * team acknowledging an over-budget warning is not changing a number, and pretending
     * otherwise would put a model's opinion into the sprint record.</p>
     */
    private void applyToItem(AiPrediction prediction, FeedbackAction action, BigDecimal finalValue) {
        if (prediction.getModelCode() != AiModelCode.M1_STORY_POINTS
                || prediction.getEntityId() == null || finalValue == null) {
            return;
        }
        // The backlog module records the activity entry itself, with the same wording a
        // manual estimate produces. Writing one here too would give the story two history
        // rows for one decision.
        stories.applyEstimate(prediction.getEntityId(), finalValue, action == FeedbackAction.ACCEPTED);
    }
}
