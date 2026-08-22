package com.sprintiq.pms.modules.ai.web.dto;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.ai.domain.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The AI module's wire contract.
 *
 * <p>Every capability answers in the same envelope: a value, its uncertainty, ranked
 * drivers, a plain-language explanation, and the prediction id needed to send feedback
 * back. That uniformity is a product decision, not a convenience — the panels are meant
 * to look and behave alike so that a team learns one interaction (Accept / Override /
 * Compare) and applies it everywhere.</p>
 */
public final class AiDtos {

    private AiDtos() {
    }

    // ------------------------------------------------------------------- envelope

    @Schema(name = "PredictionDriverResponse", description = "One ranked reason behind a prediction")
    public record PredictionDriverResponse(String feature,
                                           String label,
                                           BigDecimal contribution,
                                           String direction,
                                           String detail) {

        public static PredictionDriverResponse from(PredictionDriver driver) {
            return new PredictionDriverResponse(driver.feature(), driver.label(),
                    driver.contribution(), driver.direction(), driver.detail());
        }
    }

    /**
     * The common shape. {@code degraded} tells the UI to present the number with a
     * caveat rather than hiding it — a slow answer is still an answer, and silently
     * dropping it teaches users that the panel is unreliable.
     */
    @Schema(name = "PredictionEnvelope", description = "Standard AI response envelope")
    public record PredictionEnvelope(UUID predictionId,
                                     AiModelCode modelCode,
                                     String modelVersion,
                                     InferenceMode mode,
                                     EntityType entityType,
                                     UUID entityId,
                                     BigDecimal value,
                                     String label,
                                     BigDecimal confidence,
                                     BigDecimal lowerBound,
                                     BigDecimal upperBound,
                                     List<PredictionDriverResponse> drivers,
                                     String explanation,
                                     PredictionStatus status,
                                     boolean degraded,
                                     boolean cacheHit,
                                     Integer latencyMs,
                                     Instant createdAt) {

        public static PredictionEnvelope from(AiPrediction prediction) {
            return new PredictionEnvelope(
                    prediction.getId(), prediction.getModelCode(), prediction.getModelVersion(),
                    prediction.getMode(), prediction.getEntityType(), prediction.getEntityId(),
                    prediction.getPredictedValue(), prediction.getPredictedLabel(),
                    prediction.getConfidence(), prediction.getLowerBound(), prediction.getUpperBound(),
                    prediction.getDrivers().stream().map(PredictionDriverResponse::from).toList(),
                    prediction.getExplanation(), prediction.getStatus(),
                    prediction.getStatus() == PredictionStatus.DEGRADED,
                    prediction.isCacheHit(), prediction.getLatencyMs(), prediction.getCreatedAt());
        }
    }

    @Schema(name = "PredictionDetailResponse", description = "A prediction with its stored feature vector")
    public record PredictionDetailResponse(PredictionEnvelope prediction,
                                           UUID projectId,
                                           Map<String, Object> features,
                                           String errorMessage,
                                           FeedbackResponse feedback) {}

    // ----------------------------------------------------------------- M1 · sizing

    @Schema(name = "EstimateStoryPointsRequest")
    public record EstimateStoryPointsRequest(@NotNull UUID storyId) {}

    @Schema(name = "BatchEstimateRequest")
    public record BatchEstimateRequest(@NotEmpty @Size(max = 50) List<@NotNull UUID> storyIds) {}

    @Schema(name = "StoryPointEstimateResponse")
    public record StoryPointEstimateResponse(UUID storyId,
                                             String itemKey,
                                             String title,
                                             BigDecimal currentPoints,
                                             BigDecimal suggestedPoints,
                                             PredictionEnvelope prediction) {}

    @Schema(name = "BatchEstimateResponse")
    public record BatchEstimateResponse(List<StoryPointEstimateResponse> estimates,
                                        int requested,
                                        int succeeded) {}

    @Schema(name = "PredictVelocityRequest")
    public record PredictVelocityRequest(@NotNull UUID sprintId) {}

    @Schema(name = "VelocityPredictionResponse")
    public record VelocityPredictionResponse(UUID sprintId,
                                             BigDecimal predictedVelocity,
                                             BigDecimal rollingAverage,
                                             BigDecimal committedPoints,
                                             BigDecimal capacityPoints,
                                             /** Committed points as a share of the prediction. */
                                             BigDecimal commitmentRatio,
                                             String commitmentVerdict,
                                             PredictionEnvelope prediction) {}

    // ------------------------------------------------------------------ M2 · risk

    @Schema(name = "AssessRiskRequest")
    public record AssessRiskRequest(@NotNull UUID sprintId) {}

    @Schema(name = "RiskAssessmentResponse")
    public record RiskAssessmentResponse(UUID id,
                                         UUID sprintId,
                                         RiskType riskType,
                                         BigDecimal riskScore,
                                         boolean atRisk,
                                         RiskSeverity severity,
                                         BigDecimal confidence,
                                         List<PredictionDriverResponse> drivers,
                                         List<String> mitigations,
                                         String explanation,
                                         UUID predictionId,
                                         UUID acknowledgedBy,
                                         Instant acknowledgedAt,
                                         Instant createdAt) {

        public static RiskAssessmentResponse from(RiskAssessment assessment) {
            return new RiskAssessmentResponse(assessment.getId(), assessment.getSprintId(),
                    assessment.getRiskType(), assessment.getRiskScore(), assessment.isRiskLabel(),
                    assessment.getSeverity(), assessment.getConfidence(),
                    assessment.getDrivers().stream().map(PredictionDriverResponse::from).toList(),
                    assessment.getMitigations(), assessment.getExplanation(),
                    assessment.getPredictionId(), assessment.getAcknowledgedBy(),
                    assessment.getAcknowledgedAt(), assessment.getCreatedAt());
        }
    }

    @Schema(name = "SprintRiskResponse", description = "All three risk types for one sprint")
    public record SprintRiskResponse(UUID sprintId,
                                     RiskAssessmentResponse overBudget,
                                     RiskAssessmentResponse requirementChange,
                                     /**
                                      * Null until the sprint has some collaboration to
                                      * read — a comment, a blocker or a reopen. An
                                      * untouched sprint is not a well-coordinated one.
                                      */
                                     RiskAssessmentResponse communicationCollaboration,
                                     RiskSeverity highestSeverity) {}

    // ------------------------------------------------------------ M3 · allocation

    @Schema(name = "RankCandidatesRequest")
    public record RankCandidatesRequest(@NotNull EntityType entityType,
                                        @NotNull UUID entityId,
                                        @Size(max = 50) List<UUID> candidateUserIds,
                                        @Min(1) @Max(20) Integer limit) {}

    @Schema(name = "AllocationCandidateResponse")
    public record AllocationCandidateResponse(UUID userId,
                                              String fullName,
                                              String avatarUrl,
                                              short rankPosition,
                                              BigDecimal fitScore,
                                              BigDecimal predictedPerformance,
                                              BigDecimal consistencyScore,
                                              BigDecimal learningRate,
                                              BigDecimal skillMatchRatio,
                                              BigDecimal currentLoadPct,
                                              boolean overloadRisk,
                                              List<PredictionDriverResponse> reasoning,
                                              String explanation) {}

    @Schema(name = "AllocationRankingResponse")
    public record AllocationRankingResponse(EntityType entityType,
                                            UUID entityId,
                                            String itemKey,
                                            UUID currentAssigneeId,
                                            List<String> requiredSkills,
                                            List<AllocationCandidateResponse> candidates,
                                            UUID predictionId,
                                            Instant createdAt) {}

    // ------------------------------------------------------------------ M4 · cost

    @Schema(name = "ForecastCostRequest")
    public record ForecastCostRequest(@NotNull UUID sprintId) {}

    @Schema(name = "CostForecastResponse")
    public record CostForecastResponse(UUID id,
                                       UUID sprintId,
                                       CostStage stage,
                                       BigDecimal forecastAmount,
                                       BigDecimal errorMarginAmount,
                                       BigDecimal lowerBound,
                                       BigDecimal upperBound,
                                       String currency,
                                       BigDecimal confidence,
                                       String trend,
                                       BigDecimal varianceVsBudgetPct,
                                       BigDecimal budgetAmount,
                                       List<PredictionDriverResponse> drivers,
                                       String explanation,
                                       UUID predictionId,
                                       Instant snapshotAt) {

        public static CostForecastResponse from(CostForecast forecast, BigDecimal budgetAmount) {
            return new CostForecastResponse(forecast.getId(), forecast.getSprintId(), forecast.getStage(),
                    forecast.getForecastAmount(), forecast.getErrorMarginAmount(), forecast.getLowerBound(),
                    forecast.getUpperBound(), forecast.getCurrency(), forecast.getConfidence(),
                    forecast.getTrend(), forecast.getVarianceVsBudgetPct(), budgetAmount,
                    forecast.getDrivers().stream().map(PredictionDriverResponse::from).toList(),
                    forecast.getExplanation(), forecast.getPredictionId(), forecast.getSnapshotAt());
        }
    }

    @Schema(name = "CostTrendPoint")
    public record CostTrendPoint(UUID sprintId,
                                 String sprintName,
                                 CostStage stage,
                                 BigDecimal forecastAmount,
                                 BigDecimal lowerBound,
                                 BigDecimal upperBound,
                                 BigDecimal budgetAmount,
                                 Instant snapshotAt) {}

    @Schema(name = "CostPredictionAccuracy", description = "Accuracy of one stored cost forecast against actual sprint cost")
    public record CostPredictionAccuracy(UUID forecastId,
                                         CostStage stage,
                                         BigDecimal predictedCost,
                                         BigDecimal actualCost,
                                         BigDecimal errorAmount,
                                         BigDecimal absoluteError,
                                         BigDecimal absolutePercentageError,
                                         Instant snapshotAt) {}

    @Schema(name = "CostValidationResponse",
            description = "Live cost tracking for an active sprint or final prediction accuracy for a completed sprint")
    public record CostValidationResponse(UUID sprintId,
                                         String evaluationStatus,
                                         boolean accuracyEligible,
                                         BigDecimal actualCost,
                                         BigDecimal actualCostSoFar,
                                         BigDecimal estimatedRemainingCost,
                                         BigDecimal projectedFinalCost,
                                         BigDecimal latestForecastAmount,
                                         BigDecimal liveVariance,
                                         BigDecimal mae,
                                         BigDecimal rmse,
                                         BigDecimal mape,
                                         CostPredictionAccuracy initial,
                                         CostPredictionAccuracy midSprint,
                                         CostStage closerStage,
                                         List<CostPredictionAccuracy> predictions) {}

    // -------------------------------------------------------------- feedback loop

    @Schema(name = "SubmitFeedbackRequest",
            description = "Accept, override or dismiss a suggestion. finalValue is required for OVERRIDDEN.")
    public record SubmitFeedbackRequest(@NotNull FeedbackAction action,
                                        BigDecimal finalValue,
                                        @Size(max = 500) String reason,
                                        /** Write the accepted value through to the underlying item. */
                                        Boolean apply) {}

    @Schema(name = "FeedbackResponse")
    public record FeedbackResponse(UUID id,
                                   UUID predictionId,
                                   FeedbackAction action,
                                   BigDecimal predictedValue,
                                   BigDecimal finalValue,
                                   BigDecimal delta,
                                   String reason,
                                   UUID userId,
                                   Instant createdAt) {

        public static FeedbackResponse from(AiFeedback feedback) {
            return new FeedbackResponse(feedback.getId(), feedback.getPredictionId(), feedback.getAction(),
                    feedback.getPredictedValue(), feedback.getFinalValue(), feedback.getDelta(),
                    feedback.getReason(), feedback.getUserId(), feedback.getCreatedAt());
        }
    }

    // ----------------------------------------------------------------- admin view

    @Schema(name = "AiModelResponse")
    public record AiModelResponse(AiModelCode code,
                                  String name,
                                  String description,
                                  String version,
                                  String taskType,
                                  InferenceMode defaultMode,
                                  boolean enabled,
                                  int latencyBudgetMs,
                                  String endpointUrl,
                                  String flagKey,
                                  boolean flagEnabled,
                                  ModelUsageResponse usage) {}

    @Schema(name = "ModelUsageResponse", description = "Rolling 30-day usage and acceptance")
    public record ModelUsageResponse(long predictions,
                                     long failures,
                                     long accepted,
                                     long overridden,
                                     long dismissed,
                                     BigDecimal acceptanceRate,
                                     BigDecimal meanAbsoluteError) {}

    @Schema(name = "UpdateAiModelRequest")
    public record UpdateAiModelRequest(Boolean enabled,
                                       @Size(max = 24) String version,
                                       @Size(max = 512) String endpointUrl,
                                       @Min(100) @Max(60000) Integer latencyBudgetMs) {}

    // ------------------------------------------------------------------- insights

    @Schema(name = "AiInsightsResponse", description = "Everything the AI dashboard shows for one scope")
    public record AiInsightsResponse(UUID scopeId,
                                     String scopeType,
                                     String scopeName,
                                     VelocityPredictionResponse velocity,
                                     SprintRiskResponse risk,
                                     CostForecastResponse cost,
                                     List<CostTrendPoint> costTrend,
                                     List<RiskAssessmentResponse> recentRisks,
                                     EstimationCoverageResponse estimationCoverage,
                                     List<String> disabledCapabilities) {}

    @Schema(name = "EstimationCoverageResponse",
            description = "How much of the scope carries an estimate, and where it came from")
    public record EstimationCoverageResponse(long totalStories,
                                             long estimated,
                                             long aiAssisted,
                                             BigDecimal coveragePct,
                                             BigDecimal aiAcceptanceRate) {}
}
