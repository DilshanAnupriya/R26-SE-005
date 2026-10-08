package com.sprintiq.pms.modules.copilot.application.tools;

import com.sprintiq.pms.modules.copilot.application.ProposalService;
import com.sprintiq.pms.modules.copilot.domain.ProposalType;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ProposalResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools that suggest a change. None of them makes one.
 *
 * <p>Each result says so in words the model will repeat — "waiting for you to confirm"
 * — because the failure this guards against is an assistant reporting "done" about a
 * change that has not happened.</p>
 */
@Configuration
public class ProposalTools {

    private static final String NOTE = "Nothing has changed yet. The user will see a card with "
            + "this summary and a Confirm button. Tell them it is waiting for their confirmation.";

    static Map<String, Object> result(ProposalResponse p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("proposalId", p.id());
        m.put("summary", p.summary());
        m.put("status", p.status());
        m.put("note", NOTE);
        return m;
    }

    private static final Map<String, Object> REASON =
            ToolSchemas.string("One sentence on why, quoting the figure it rests on");

    @Bean
    CopilotTool proposeAcceptEstimateTool(@Lazy ProposalService proposals) {
        return SimpleTool.builder()
                .name("propose_accept_estimate")
                .label("Proposed accepting an estimate")
                .description("Propose setting a story's points to an M1 suggestion. Requires the "
                        + "predictionId from estimate_story_points. Creates a card the user must "
                        + "confirm; does not change the story.")
                .parameters(ToolSchemas.object(Map.of(
                        "predictionId", ToolSchemas.uuid("prediction.predictionId from estimate_story_points"),
                        "reason", REASON), List.of("predictionId")))
                .requiredPermission(ProposalService.permissionFor(ProposalType.ACCEPT_ESTIMATE))
                .handler((args, ctx) -> result(proposals.proposeAcceptEstimate(ctx,
                        ToolArgs.requireUuid(args, "predictionId"), ToolArgs.optionalString(args, "reason"))))
                .build();
    }

    @Bean
    CopilotTool proposeAssignTaskTool(@Lazy ProposalService proposals) {
        return SimpleTool.builder()
                .name("propose_assign_task")
                .label("Proposed an assignment")
                .description("Propose assigning a task to a person, usually a candidate from "
                        + "rank_developers_for_task (pass its predictionId so the assignment is "
                        + "recorded as AI-suggested). Creates a card the user must confirm.")
                .parameters(ToolSchemas.object(Map.of(
                        "taskId", ToolSchemas.uuid("Task id"),
                        "assigneeUserId", ToolSchemas.uuid("The person's userId"),
                        "predictionId", ToolSchemas.uuid("Optional predictionId from rank_developers_for_task"),
                        "reason", REASON), List.of("taskId", "assigneeUserId")))
                .requiredPermission(ProposalService.permissionFor(ProposalType.ASSIGN_TASK))
                .handler((args, ctx) -> result(proposals.proposeAssignTask(ctx,
                        ToolArgs.requireUuid(args, "taskId"),
                        ToolArgs.requireUuid(args, "assigneeUserId"),
                        ToolArgs.optionalUuid(args, "predictionId"),
                        ToolArgs.optionalString(args, "reason"))))
                .build();
    }

    @Bean
    CopilotTool proposeMoveToSprintTool(@Lazy ProposalService proposals) {
        return SimpleTool.builder()
                .name("propose_move_story_to_sprint")
                .label("Proposed a sprint change")
                .description("Propose adding backlog stories (same project, at most 20) to a "
                        + "planned or active sprint. Check capacity with forecast_velocity first "
                        + "when it matters. Creates a card the user must confirm.")
                .parameters(ToolSchemas.object(Map.of(
                        "storyIds", ToolSchemas.uuidArray("Stories to add"),
                        "sprintId", ToolSchemas.uuid("Target sprint id"),
                        "reason", REASON), List.of("storyIds", "sprintId")))
                .requiredPermission(ProposalService.permissionFor(ProposalType.MOVE_TO_SPRINT))
                .handler((args, ctx) -> result(proposals.proposeMoveToSprint(ctx,
                        ToolArgs.requireUuidList(args, "storyIds"),
                        ToolArgs.requireUuid(args, "sprintId"),
                        ToolArgs.optionalString(args, "reason"))))
                .build();
    }
}
