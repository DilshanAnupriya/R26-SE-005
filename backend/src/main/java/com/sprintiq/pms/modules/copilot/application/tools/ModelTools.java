package com.sprintiq.pms.modules.copilot.application.tools;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.ai.application.AllocationService;
import com.sprintiq.pms.modules.ai.application.CostForecastService;
import com.sprintiq.pms.modules.ai.application.EstimationService;
import com.sprintiq.pms.modules.ai.application.RiskService;
import com.sprintiq.pms.modules.ai.domain.CostStage;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.SprintRiskResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The four AI models, as tools.
 *
 * <p>Each description carries the model's known weaknesses, in the same words the risk
 * advisor's prompt uses. The model reads these every time it decides which tool to
 * call, which makes them the most reliable place to tell it how far to trust a number.</p>
 */
@Configuration
public class ModelTools {

    static final String AI = "ai:predict";

    @Bean
    CopilotTool estimateStoryPointsTool(EstimationService estimation) {
        return SimpleTool.builder()
                .name("estimate_story_points")
                .label("Estimated story points (M1)")
                .description("Model M1: suggest story points for one story, with bounds, ranked "
                        + "drivers and an explanation. Does not change the story. The result's "
                        + "prediction.predictionId is what propose_accept_estimate needs.")
                .parameters(ToolSchemas.object(Map.of(
                        "storyId", ToolSchemas.uuid("Story id")), List.of("storyId")))
                .requiredPermission(AI)
                .handler((args, ctx) -> estimation.estimate(ToolArgs.requireUuid(args, "storyId")))
                .build();
    }

    @Bean
    CopilotTool forecastVelocityTool(EstimationService estimation) {
        return SimpleTool.builder()
                .name("forecast_velocity")
                .label("Forecast sprint velocity (M1)")
                .description("Model M1: predicted velocity for a sprint and a verdict on whether "
                        + "the commitment is realistic against it.")
                .parameters(ToolSchemas.object(Map.of(
                        "sprintId", ToolSchemas.uuid("Sprint id")), List.of("sprintId")))
                .requiredPermission(AI)
                .handler((args, ctx) -> estimation.predictVelocity(ToolArgs.requireUuid(args, "sprintId")))
                .build();
    }

    @Bean
    CopilotTool sprintRiskTool(RiskService risk) {
        return SimpleTool.builder()
                .name("get_sprint_risk")
                .label("Read sprint risk (M2)")
                .description("Model M2: over-budget, requirement-change and communication risk for a "
                        + "sprint, the weighted overall figure, and the board findings adjusting "
                        + "them. Over-budget is well calibrated (0.91 AUC); requirement-change is "
                        + "weak (F1 0.46) and misses more high-change sprints than it catches — "
                        + "say so when it matters. Reads the latest stored scores; pass "
                        + "refresh=true only when asked to re-score.")
                .parameters(ToolSchemas.object(Map.of(
                        "sprintId", ToolSchemas.uuid("Sprint id"),
                        "refresh", ToolSchemas.bool("Re-run the models instead of reading stored scores")),
                        List.of("sprintId")))
                .requiredPermission(AI)
                .handler((args, ctx) -> {
                    UUID sprintId = ToolArgs.requireUuid(args, "sprintId");
                    SprintRiskResponse result = ToolArgs.optionalBoolean(args, "refresh")
                            ? risk.assess(sprintId) : risk.latest(sprintId);
                    // Never scored: score it now rather than report "no risk", which a
                    // reader would take as good news.
                    if (result.overall() == null) {
                        result = risk.assess(sprintId);
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("risk", result);
                    m.put("boardFindings", risk.adjustments(sprintId));
                    return m;
                })
                .build();
    }

    @Bean
    CopilotTool rankDevelopersTool(AllocationService allocation) {
        return SimpleTool.builder()
                .name("rank_developers_for_task")
                .label("Ranked developers (M3)")
                .description("Model M3: rank team members for a task or story by fit, with "
                        + "consistency, learning rate, skill match and current load. Give exactly "
                        + "one of taskId or storyId. Does not assign anyone. Never describe a "
                        + "person as good or bad — these are fit scores for one item.")
                .parameters(ToolSchemas.object(Map.of(
                        "taskId", ToolSchemas.uuid("Task id"),
                        "storyId", ToolSchemas.uuid("Story id"),
                        "limit", ToolSchemas.integer("How many candidates (1-10, default 5)")),
                        List.of()))
                .requiredPermission(AI)
                .handler((args, ctx) -> {
                    UUID taskId = ToolArgs.optionalUuid(args, "taskId");
                    UUID storyId = ToolArgs.optionalUuid(args, "storyId");
                    if ((taskId == null) == (storyId == null)) {
                        throw new InvalidToolArgumentsException("Give exactly one of taskId or storyId.");
                    }
                    Integer limit = ToolArgs.optionalInt(args, "limit", 1, 10);
                    return taskId != null
                            ? allocation.rank(EntityType.TASK, taskId, null, limit == null ? 5 : limit)
                            : allocation.rank(EntityType.STORY, storyId, null, limit == null ? 5 : limit);
                })
                .build();
    }

    @Bean
    CopilotTool forecastSprintCostTool(CostForecastService cost) {
        return SimpleTool.builder()
                .name("forecast_sprint_cost")
                .label("Forecast sprint cost (M4)")
                .description("Model M4: forecast a sprint's cost with bounds and drivers, compared "
                        + "with its budget. INITIAL is for planning a sprint not yet started; "
                        + "MID_SPRINT for one in progress. Typical error is 23-29%, so a forecast "
                        + "within about a fifth of budget is not a meaningful overrun.")
                .parameters(ToolSchemas.object(Map.of(
                        "sprintId", ToolSchemas.uuid("Sprint id"),
                        "stage", ToolSchemas.enumOf("INITIAL or MID_SPRINT", CostStage.values())),
                        List.of("sprintId", "stage")))
                .requiredPermission(AI)
                .handler((args, ctx) -> {
                    CostStage stage = ToolArgs.optionalEnum(args, "stage", CostStage.class);
                    if (stage == null) throw new InvalidToolArgumentsException("stage is required.");
                    return cost.forecast(ToolArgs.requireUuid(args, "sprintId"), stage);
                })
                .build();
    }
}
