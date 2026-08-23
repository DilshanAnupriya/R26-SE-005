package com.sprintiq.pms.modules.ai.web;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.ai.application.*;
import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.CostStage;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The four AI capabilities, their prediction history and the feedback loop.
 *
 * <p>Everything that produces a suggestion sits behind {@code ai:predict}; everything
 * that changes how the models are configured sits behind {@code ai:manage} on the admin
 * controller. Reading a prediction someone else generated needs only the permission on
 * the item it describes, which is why the history endpoints require {@code ai:predict}
 * rather than an administrative right — a developer must be able to see why their story
 * was sized at 8.</p>
 */
@Tag(name = "AI", description = "Estimation, risk, allocation and cost models")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/ai")
@RequiredArgsConstructor
public class AiController {

    private final EstimationService estimation;
    private final RiskService risk;
    private final AllocationService allocation;
    private final CostForecastService cost;
    private final AiFeedbackService feedback;
    private final AiInsightsService insights;
    private final SprintRiskTrendService riskTrend;
    private final RiskAdvisorService advisor;

    // -------------------------------------------------------- M1 · estimation

    @Operation(summary = "Suggest story points",
            description = "Returns a suggestion with bounds, ranked drivers and an explanation. "
                    + "The story's own estimate is not changed until the suggestion is accepted.")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/estimation/story-points")
    public StoryPointEstimateResponse estimateStoryPoints(
            @Valid @RequestBody EstimateStoryPointsRequest request) {
        return estimation.estimate(request.storyId());
    }

    @Operation(summary = "Suggest story points for many stories",
            description = "Partial success: stories that cannot be estimated come back with a null "
                    + "suggestion rather than failing the batch.")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/estimation/story-points/batch")
    public BatchEstimateResponse estimateBatch(@Valid @RequestBody BatchEstimateRequest request) {
        return estimation.estimateBatch(request.storyIds());
    }

    @Operation(summary = "Predict sprint velocity and judge the commitment against it")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/estimation/velocity")
    public VelocityPredictionResponse predictVelocity(
            @Valid @RequestBody PredictVelocityRequest request) {
        return estimation.predictVelocity(request.sprintId());
    }

    // -------------------------------------------------------------- M2 · risk

    @Operation(summary = "Assess over-budget risk for a sprint")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/risk/over-budget")
    public RiskAssessmentResponse overBudgetRisk(@Valid @RequestBody AssessRiskRequest request) {
        return risk.assess(request.sprintId(), RiskType.OVER_BUDGET);
    }

    @Operation(summary = "Assess requirement-change risk for a sprint")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/risk/requirement-change")
    public RiskAssessmentResponse requirementChangeRisk(@Valid @RequestBody AssessRiskRequest request) {
        return risk.assess(request.sprintId(), RiskType.REQUIREMENT_CHANGE);
    }

    @Operation(summary = "Assess communication and collaboration risk for a sprint",
            description = "Answers with an empty body when the sprint has no "
                    + "collaboration to read yet — no comments, no blockers, no reopens. "
                    + "Three of the five readings are healthiest at zero, so scoring an "
                    + "untouched sprint would report a well-run team rather than an "
                    + "unstarted one.")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/risk/communication-collaboration")
    public RiskAssessmentResponse communicationCollaborationRisk(
            @Valid @RequestBody AssessRiskRequest request) {
        return risk.assess(request.sprintId(), RiskType.COMMUNICATION_COLLABORATION);
    }

    @Operation(summary = "Assess all three risks for a sprint in one call")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/risk/sprint/{sprintId}/assess")
    public SprintRiskResponse assessSprint(@PathVariable UUID sprintId) {
        return risk.assess(sprintId);
    }

    @Operation(summary = "Latest stored risk assessments for a sprint")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/risk/sprint/{sprintId}")
    public SprintRiskResponse latestRisk(@PathVariable UUID sprintId) {
        return risk.latest(sprintId);
    }

    @Operation(summary = "How a risk score moved over the sprint")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/risk/sprint/{sprintId}/history")
    public List<RiskAssessmentResponse> riskHistory(@PathVariable UUID sprintId,
                                                    @RequestParam RiskType riskType) {
        return risk.history(sprintId, riskType);
    }

    @Operation(summary = "Risk per day of the sprint",
            description = "One point per calendar day, forward-filled from the last scoring at or "
                    + "before 17:00 UTC that day. Days before the first assessment and days still "
                    + "to come are marked hasData=false so the chart draws them as gaps rather "
                    + "than as zero risk. Today's point carries the board adjustments, so the end "
                    + "of the line agrees with the gauges.")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/risk/sprint/{sprintId}/daily-trend")
    public List<DailyRiskPoint> dailyRiskTrend(@PathVariable UUID sprintId) {
        return riskTrend.dailyTrend(sprintId);
    }

    @Operation(summary = "The board activity behind an adjusted risk score",
            description = "Newest first. Every point in the difference between the model's own "
                    + "figure and the one on screen has a row here.")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/risk/sprint/{sprintId}/adjustments")
    public List<RiskAdjustmentResponse> riskAdjustments(@PathVariable UUID sprintId) {
        return risk.adjustments(sprintId);
    }

    @Operation(summary = "Narrative advice over a sprint's risk",
            description = "Turns the scores and findings into prose a scrum master can act on. "
                    + "Unlike every other model here this one calls an external provider, so it "
                    + "is disabled unless an API key is configured — in which case the response "
                    + "says so rather than failing.")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/risk/sprint/{sprintId}/advise")
    public RiskAdvisorResponse advise(@PathVariable UUID sprintId) {
        return advisor.advise(risk.latest(sprintId), risk.adjustments(sprintId));
    }

    @Operation(summary = "Compare the risk profile of several sprints",
            description = "Two to five sprints side by side. A sprint that was never assessed "
                    + "comes back with null scores rather than being dropped — that it was never "
                    + "looked at is itself worth seeing.")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/risk/compare")
    public List<SprintRiskComparison> compareSprintRisk(
            @RequestParam List<UUID> sprintIds) {
        return riskTrend.compare(sprintIds);
    }

    @Operation(summary = "Acknowledge a risk",
            description = "Stops the warning being presented as unseen. The assessment itself is "
                    + "not altered.")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/risk/{assessmentId}/acknowledge")
    public RiskAssessmentResponse acknowledgeRisk(@PathVariable UUID assessmentId) {
        return risk.acknowledge(assessmentId);
    }

    // -------------------------------------------------------- M3 · allocation

    @Operation(summary = "Rank candidates for a work item",
            description = "Suggestion only — nothing is assigned. Each candidate carries their own "
                    + "reasoning, including current workload.")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/allocation/rank")
    public AllocationRankingResponse rank(@Valid @RequestBody RankCandidatesRequest request) {
        return allocation.rank(request.entityType(), request.entityId(),
                request.candidateUserIds(), request.limit());
    }

    @Operation(summary = "Suggest an assignee for a task")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/allocation/task/{taskId}/suggest")
    public AllocationRankingResponse suggestForTask(@PathVariable UUID taskId,
                                                    @RequestParam(required = false) Integer limit) {
        return allocation.rank(EntityType.TASK, taskId, null, limit);
    }

    @Operation(summary = "Suggest an assignee for a story")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/allocation/story/{storyId}/suggest")
    public AllocationRankingResponse suggestForStory(@PathVariable UUID storyId,
                                                     @RequestParam(required = false) Integer limit) {
        return allocation.rank(EntityType.STORY, storyId, null, limit);
    }

    // -------------------------------------------------------------- M4 · cost

    @Operation(summary = "Initial sprint cost forecast",
            description = "Planning only. Always returns bounds alongside the figure.")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/cost/initial")
    public CostForecastResponse initialCost(@Valid @RequestBody ForecastCostRequest request) {
        return cost.forecast(request.sprintId(), CostStage.INITIAL);
    }

    @Operation(summary = "Mid-sprint cost re-forecast",
            description = "Extrapolates from logged effort. Falls back to the initial model when the "
                    + "sprint is too young to extrapolate from.")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/cost/mid-sprint")
    public CostForecastResponse midSprintCost(@Valid @RequestBody ForecastCostRequest request) {
        return cost.forecast(request.sprintId(), CostStage.MID_SPRINT);
    }

    @Operation(summary = "Forecast history for a sprint")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/cost/sprint/{sprintId}/history")
    public List<CostTrendPoint> costHistory(@PathVariable UUID sprintId) {
        return cost.sprintHistory(sprintId);
    }

    @Operation(summary = "Track active sprint cost or validate completed sprint forecasts",
            description = "Accuracy metrics are returned only after the sprint is completed. Active sprints "
                    + "return actual cost so far, estimated remaining cost and projected final cost.")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/cost/sprint/{sprintId}/validation")
    public CostValidationResponse costValidation(@PathVariable UUID sprintId) {
        return cost.validateSprint(sprintId);
    }

    // -------------------------------------------------- predictions & feedback

    @Operation(summary = "Search predictions")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/predictions")
    public PageResponse<PredictionEnvelope> predictions(
            @RequestParam(required = false) AiModelCode modelCode,
            @RequestParam(required = false) EntityType entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Valid PageQuery query) {
        return feedback.search(modelCode, entityType, entityId, projectId, from, to, query);
    }

    @Operation(summary = "One prediction with the feature vector it was computed from",
            description = "The stored payload is what the model actually saw, not a re-read of the "
                    + "item as it stands today.")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/predictions/{predictionId}")
    public PredictionDetailResponse prediction(@PathVariable UUID predictionId) {
        return feedback.detail(predictionId);
    }

    @Operation(summary = "Accept, override or dismiss a suggestion",
            description = "Accepting an estimate writes it through to the story. Every verdict is "
                    + "recorded as training signal.")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/predictions/{predictionId}/feedback")
    public FeedbackResponse submitFeedback(@PathVariable UUID predictionId,
                                           @Valid @RequestBody SubmitFeedbackRequest request) {
        return feedback.submit(predictionId, request);
    }

    // ------------------------------------------------------------- dashboards

    @Operation(summary = "AI insights for a project")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/insights/project/{projectId}")
    public AiInsightsResponse projectInsights(@PathVariable UUID projectId) {
        return insights.forProject(projectId);
    }

    @Operation(summary = "AI insights for a sprint")
    @PreAuthorize("@perm.has('ai:predict')")
    @GetMapping("/insights/sprint/{sprintId}")
    public AiInsightsResponse sprintInsights(@PathVariable UUID sprintId) {
        return insights.forSprint(sprintId);
    }

    @Operation(summary = "Re-run every enabled model for a sprint")
    @PreAuthorize("@perm.has('ai:predict')")
    @PostMapping("/insights/sprint/{sprintId}/refresh")
    public AiInsightsResponse refreshSprintInsights(@PathVariable UUID sprintId) {
        return insights.refreshSprint(sprintId);
    }
}
