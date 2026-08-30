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
                                     /**
                                      * The model's own number, before it was snapped to the
                                      * nearest Fibonacci point.
                                      *
                                      * <p>Story points are reported on a scale of 1, 2, 3, 5, 8,
                                      * 13, so everything the model puts between 6.5 and 10.5
                                      * comes back as an 8. Two stories the model rated 6.8 and
                                      * 9.9 — a difference of nearly half again — are
                                      * indistinguishable once snapped, and a planner rightly
                                      * concludes the model always says 8.</p>
                                      *
                                      * <p>Carried so the panel can show which 8s sit in the
                                      * middle of their band and which are one small nudge from
                                      * being a 5 or a 13. Null for models that do not snap.</p>
                                      */
                                     BigDecimal rawValue,
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
                    rawValueOf(prediction),
                    prediction.getDrivers().stream().map(PredictionDriverResponse::from).toList(),
                    prediction.getExplanation(), prediction.getStatus(),
                    prediction.getStatus() == PredictionStatus.DEGRADED,
                    prediction.isCacheHit(), prediction.getLatencyMs(), prediction.getCreatedAt());
        }

        /**
         * Digs the pre-snap number out of the stored model response.
         *
         * <p>Read from {@code rawResponse} rather than given a column of its own: it is
         * presentation detail for one model, it is already persisted with everything else
         * the model said, and a migration to surface a number we are choosing to display
         * differently would be the wrong trade.</p>
         */
        private static BigDecimal rawValueOf(AiPrediction prediction) {
            Map<String, Object> response = prediction.getRawResponse();
            if (response == null || !(response.get("raw") instanceof Map<?, ?> raw)) {
                return null;
            }
            return raw.get("point") instanceof Number point
                    ? BigDecimal.valueOf(point.doubleValue())
                    : null;
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
                                         Instant createdAt,
                                         /**
                                          * Points the board has added since this scoring — a task
                                          * blocked, work reopened, scope logged.
                                          *
                                          * <p>On a 0–100 scale, unlike {@code riskScore}, which is
                                          * a 0–1 probability. Deliberately: the ledger is written
                                          * and read by people ("scope added, +8"), and expressing
                                          * it in hundredths of a probability would make every
                                          * entry unreadable to make one division unnecessary.</p>
                                          */
                                         BigDecimal adjustmentDelta,
                                         /**
                                          * What the score reads as now: {@code riskScore} plus the
                                          * adjustment converted to the same 0–1 scale, clamped.
                                          *
                                          * <p>Sent alongside the model's own figure rather than
                                          * replacing it. A dashboard showing only the adjusted
                                          * number cannot be reconciled with the prediction record,
                                          * and the first person to try would stop trusting both.</p>
                                          */
                                         BigDecimal adjustedScore,
                                         /** {@code adjustedScore} banded — what the badge should say. */
                                         RiskSeverity adjustedSeverity) {

        /** Divides the human-scale ledger into the model's probability scale. */
        private static final BigDecimal POINTS_PER_UNIT = BigDecimal.valueOf(100);

        public static RiskAssessmentResponse from(RiskAssessment assessment) {
            return from(assessment, BigDecimal.ZERO);
        }

        public static RiskAssessmentResponse from(RiskAssessment assessment, BigDecimal adjustment) {
            BigDecimal delta = adjustment == null ? BigDecimal.ZERO : adjustment;
            BigDecimal base = assessment.getRiskScore() == null ? BigDecimal.ZERO : assessment.getRiskScore();
            BigDecimal adjusted = base
                    .add(delta.divide(POINTS_PER_UNIT, 4, java.math.RoundingMode.HALF_UP))
                    .max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    .setScale(4, java.math.RoundingMode.HALF_UP);
            return new RiskAssessmentResponse(assessment.getId(), assessment.getSprintId(),
                    assessment.getRiskType(), assessment.getRiskScore(), assessment.isRiskLabel(),
                    assessment.getSeverity(), assessment.getConfidence(),
                    assessment.getDrivers().stream().map(PredictionDriverResponse::from).toList(),
                    assessment.getMitigations(), assessment.getExplanation(),
                    assessment.getPredictionId(), assessment.getAcknowledgedBy(),
                    assessment.getAcknowledgedAt(), assessment.getCreatedAt(),
                    delta.setScale(2, java.math.RoundingMode.HALF_UP), adjusted,
                    RiskSeverity.forScore(adjusted));
        }
    }

    @Schema(name = "RiskRemediationStepResponse",
            description = "One concrete move against one driver of one risk, and what "
                    + "completing it is currently worth")
    public record RiskRemediationStepResponse(RiskType riskType,
                                              /** Machine name of the driver this addresses. */
                                              String driverFeature,
                                              /** The driver as the explanation panel names it. */
                                              String driverLabel,
                                              /**
                                               * Identifies the step within its risk:
                                               * {@code <feature>:<step>}. The client sends
                                               * this back to toggle it and nothing else —
                                               * the points are the server's to decide.
                                               */
                                              String stepKey,
                                              String title,
                                              String detail,
                                              /**
                                               * Ledger points this step returns while it
                                               * stands, as a positive magnitude on the
                                               * 0–100 scale.
                                               *
                                               * <p>Measured, not apportioned: the model
                                               * is re-run with this one reading moved,
                                               * and this is the fall in its prediction.
                                               * Recorded as a negative adjustment, so the
                                               * gauge drops by it until the next
                                               * assessment measures what the step
                                               * actually achieved.</p>
                                               */
                                              BigDecimal points,
                                              /** The reading as it stands, e.g. {@code 34}. */
                                              String currentValue,
                                              /**
                                               * What the search found: the value of this
                                               * reading at which the model scores the
                                               * sprint {@code points} lower. Not a
                                               * threshold anybody configured — the best
                                               * rung of a bounded ladder the model was
                                               * asked about.
                                               */
                                              String targetValue,
                                              /** The model's probability on the sprint as it stands. */
                                              BigDecimal baselineScore,
                                              /** Its probability with only this reading moved. */
                                              BigDecimal projectedScore,
                                              /**
                                               * How to go about it, in plain words.
                                               *
                                               * <p>The only hand-written text in this
                                               * response, and it decides nothing: it
                                               * cannot make a step appear, change what
                                               * one is worth, or move the target. Null
                                               * where no method has been written for
                                               * the reading.</p>
                                               */
                                              String how,
                                              boolean done,
                                              Instant doneAt) {
    }

    @Schema(name = "AbsorbRiskChangeRequest",
            description = "Whether the sprint has been re-planned around one logged "
                    + "requirement change. The credit is the server's to decide.")
    public record AbsorbRiskChangeRequest(boolean absorbed) {}

    @Schema(name = "ToggleRiskStepRequest")
    public record ToggleRiskStepRequest(@NotNull RiskType riskType,
                                        @NotBlank String stepKey,
                                        boolean done) {
    }

    @Schema(name = "RiskAdjustmentResponse",
            description = "One entry in the ledger of board activity behind an adjusted risk score")
    public record RiskAdjustmentResponse(UUID id,
                                         UUID sprintId,
                                         RiskType riskType,
                                         BigDecimal delta,
                                         String reason,
                                         Instant createdAt,
                                         /**
                                          * What produced this entry, when something
                                          * specific did — {@code TASK_SILENT:<uuid>}.
                                          * Null for one-off events that describe a
                                          * moment rather than a standing condition.
                                          */
                                         String sourceKey,
                                         /**
                                          * The {@code sourceKey}'s prefix, so a client can
                                          * offer guidance for a kind of problem without
                                          * parsing ids out of a string. Null when the
                                          * entry has no source.
                                          */
                                         String code,
                                         /**
                                          * Whether this entry withdraws itself once the
                                          * condition stops holding. Only standing
                                          * conditions do; a logged event is history and
                                          * stays. The distinction matters to a user being
                                          * asked to fix something — it says whether doing
                                          * so will actually move the number.
                                          */
                                         boolean resolvable,
                                         /**
                                          * Whether re-scoring folds this away or it keeps
                                          * counting.
                                          *
                                          * <p>Most entries are board state the models
                                          * re-read, so pressing Assess absorbs them. A
                                          * logged requirement change is not in any feature
                                          * vector, so it stands. The panel says which,
                                          * because "this one will survive Assess" is the
                                          * difference between a number somebody trusts and
                                          * one they think is broken.</p>
                                          */
                                         boolean survivesScoring) {

        public static RiskAdjustmentResponse from(SprintRiskAdjustment adjustment) {
            String sourceKey = adjustment.getSourceKey();
            return new RiskAdjustmentResponse(adjustment.getId(), adjustment.getSprintId(),
                    adjustment.getRiskType(), adjustment.getDelta(), adjustment.getReason(),
                    adjustment.getCreatedAt(), sourceKey, codeOf(sourceKey),
                    // Keyed *and* foldable by the next scoring. A logged requirement change
                    // carries a key too, so the key alone stopped being the test the moment
                    // the register started using one — and a change nobody can un-log is
                    // exactly what this field must not describe as self-clearing.
                    sourceKey != null && !adjustment.isSurvivesScoring(),
                    adjustment.isSurvivesScoring());
        }

        private static String codeOf(String sourceKey) {
            if (sourceKey == null) {
                return null;
            }
            int colon = sourceKey.indexOf(':');
            return colon < 0 ? sourceKey : sourceKey.substring(0, colon);
        }
    }

    @Schema(name = "DailyRiskPoint",
            description = "One calendar day of a sprint, forward-filled from the last scoring at or before it")
    public record DailyRiskPoint(
            /** ISO date, e.g. 2026-08-22. */
            String date,
            BigDecimal overallScore,
            RiskSeverity overallSeverity,
            BigDecimal overBudgetScore,
            BigDecimal requirementChangeScore,
            BigDecimal communicationCollaborationScore,
            /**
             * False for a day the sprint had not yet been scored, and for days still in
             * the future. Both are rendered as gaps rather than zeroes — a chart that
             * draws an unscored day at the bottom of the axis reads as a day with no
             * risk, which is the opposite of what it means.
             */
            boolean hasData,
            /** The live row: today, carrying the adjustments the board has since accumulated. */
            boolean current) {}

    @Schema(name = "SprintRiskComparison", description = "One sprint's column in a side-by-side comparison")
    public record SprintRiskComparison(UUID sprintId,
                                       String sprintName,
                                       String status,
                                       java.time.LocalDate startDate,
                                       java.time.LocalDate endDate,
                                       BigDecimal overallScore,
                                       RiskSeverity overallSeverity,
                                       BigDecimal overBudgetScore,
                                       BigDecimal requirementChangeScore,
                                       BigDecimal communicationCollaborationScore,
                                       BigDecimal committedPoints,
                                       BigDecimal completedPoints,
                                       long blockedCount,
                                       long reopenedCount,
                                       BigDecimal adjustmentDelta,
                                       /** Null when the sprint has never been scored. */
                                       Instant assessedAt) {}

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
                                     RiskSeverity highestSeverity,
                                     /** The three weighted into one figure. Null when none scored. */
                                     OverallRiskResponse overall) {}

    @Schema(name = "RiskAdvisorResponse",
            description = "A narrative advisory over a sprint's risk, or why there is none")
    public record RiskAdvisorResponse(boolean available,
                                      /** Markdown. Null when unavailable. */
                                      String report,
                                      /** Which model wrote it, for the record. */
                                      String model,
                                      /** Why there is no advice, when there is none. */
                                      String reason) {

        public static RiskAdvisorResponse ok(String report, String model) {
            return new RiskAdvisorResponse(true, report, model, null);
        }

        /**
         * No advice, and the honest reason. Not an error: the advisory is commentary on
         * numbers the user can already see, so its absence must not read as a failure of
         * the risk screen.
         */
        public static RiskAdvisorResponse unavailable(String reason) {
            return new RiskAdvisorResponse(false, null, null, reason);
        }
    }

    @Schema(name = "OverallRiskResponse",
            description = "The three risk models weighted into a single sprint figure")
    public record OverallRiskResponse(
            /** 0–1, on the same scale as each model's own score. */
            BigDecimal score,
            RiskSeverity level,
            /**
             * True when the figure was assembled from incomplete evidence — a model
             * declined to score, or answered but was marked degraded. The level has
             * already been bumped one band; this says why.
             */
            boolean degraded,
            /** Which models actually contributed, and at what weight after renormalising. */
            Map<String, BigDecimal> weights,
            String rationale) {}

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

    @Schema(name = "CostRiskCrossCheck",
            description = "The behavioural risk model and the cost model read against each other")
    public record CostRiskCrossCheckResponse(UUID sprintId,
                                             CostRiskAgreement agreement,
                                             /** One sentence naming what the two models jointly say. */
                                             String headline,
                                             /** What that means for the sprint, and what it does not. */
                                             String detail,
                                             /** M2's over-budget probability, 0-1. Null when unscored. */
                                             BigDecimal riskScore,
                                             RiskSeverity riskSeverity,
                                             BigDecimal forecastAmount,
                                             BigDecimal budgetAmount,
                                             BigDecimal variancePct,
                                             String currency,
                                             /**
                                              * The cost model's own error rate. A variance inside this
                                              * is not evidence of an overrun, and the panel says so
                                              * rather than colouring it red.
                                              */
                                             BigDecimal marginPct,
                                             /** Whether the overrun cleared the margin and moved the score. */
                                             boolean costCharged) {}

    @Schema(name = "CostTrendPoint")
    public record CostTrendPoint(UUID sprintId,
                                 String sprintName,
                                 CostStage stage,
                                 BigDecimal forecastAmount,
                                 BigDecimal errorMarginAmount,
                                 BigDecimal lowerBound,
                                 BigDecimal upperBound,
                                 BigDecimal budgetAmount,
                                 BigDecimal confidence,
                                 List<PredictionDriverResponse> drivers,
                                 String explanation,
                                 UUID predictionId,
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
