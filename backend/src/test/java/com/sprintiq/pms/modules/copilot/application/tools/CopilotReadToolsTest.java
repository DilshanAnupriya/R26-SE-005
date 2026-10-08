package com.sprintiq.pms.modules.copilot.application.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.ai.application.AllocationService;
import com.sprintiq.pms.modules.ai.application.CostForecastService;
import com.sprintiq.pms.modules.ai.application.EstimationService;
import com.sprintiq.pms.modules.ai.application.RiskService;
import com.sprintiq.pms.modules.ai.domain.CostStage;
import com.sprintiq.pms.modules.ai.domain.RiskSeverity;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.OverallRiskResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.SprintRiskResponse;
import com.sprintiq.pms.modules.backlog.application.SprintBacklogService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import com.sprintiq.pms.modules.tasks.application.TaskService;
import com.sprintiq.pms.security.PermissionService;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read tools, wired to mocked services: each one parses what the model sends,
 * calls the service a controller would, and declares the controller's permission.
 */
@DisplayName("Copilot read tools")
class CopilotReadToolsTest {

    private static final Set<String> ALL = Set.of("project:read", "sprint:read", "backlog:read",
            "task:read", "ai:predict");

    private final ProjectService projects = mock(ProjectService.class);
    private final SprintService sprints = mock(SprintService.class);
    private final SprintBacklogService sprintBacklog = mock(SprintBacklogService.class);
    private final StoryService stories = mock(StoryService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final EstimationService estimation = mock(EstimationService.class);
    private final RiskService risk = mock(RiskService.class);
    private final AllocationService allocation = mock(AllocationService.class);
    private final CostForecastService cost = mock(CostForecastService.class);
    private final PermissionService perm = new PermissionService();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private CopilotToolRegistry registry;
    private final ToolContext ctx = new ToolContext(UUID.randomUUID(), UUID.randomUUID());

    private static void actingWith(Set<String> permissions) {
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(UUID.randomUUID()).userId(UUID.randomUUID())
                .permissions(permissions).systemContext(false).build());
    }

    @BeforeEach
    void setUp() {
        DeliveryTools delivery = new DeliveryTools();
        ModelTools models = new ModelTools();
        registry = new CopilotToolRegistry(List.of(
                delivery.listProjectsTool(projects),
                delivery.projectOverviewTool(projects),
                delivery.listSprintsTool(sprints),
                delivery.sprintSummaryTool(sprints, sprintBacklog),
                delivery.searchWorkItemsTool(stories, tasks, perm),
                models.estimateStoryPointsTool(estimation),
                models.forecastVelocityTool(estimation),
                models.sprintRiskTool(risk),
                models.rankDevelopersTool(allocation),
                models.forecastSprintCostTool(cost)), perm, mapper);
        actingWith(ALL);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static <T> PageResponse<T> emptyPage() {
        return new PageResponse<>(List.of(), 0, 20, 0, 0, false, false);
    }

    @Test
    @DisplayName("every M1–M4 tool requires ai:predict, like the AI controller")
    void modelToolsNeedAiPredict() {
        actingWith(Set.of("project:read", "sprint:read", "backlog:read", "task:read"));

        assertThat(registry.permittedSpecs()).extracting(s -> s.name())
                .containsExactlyInAnyOrder("list_projects", "get_project_overview", "list_sprints",
                        "get_sprint_summary", "search_work_items");
    }

    @Test
    @DisplayName("list_sprints passes the project and status through")
    void listSprints() {
        UUID projectId = UUID.randomUUID();
        when(sprints.search(eq(projectId), eq(SprintStatus.ACTIVE), any(PageQuery.class)))
                .thenReturn(emptyPage());

        ToolOutcome outcome = registry.run("list_sprints",
                "{\"projectId\":\"" + projectId + "\",\"status\":\"active\"}", ctx);

        assertThat(outcome.ok()).as(outcome.json()).isTrue();
        verify(sprints).search(eq(projectId), eq(SprintStatus.ACTIVE), any(PageQuery.class));
    }

    @Test
    @DisplayName("estimate_story_points calls M1 for the story")
    void estimate() {
        UUID storyId = UUID.randomUUID();
        registry.run("estimate_story_points", "{\"storyId\":\"" + storyId + "\"}", ctx);
        verify(estimation).estimate(storyId);
    }

    @Test
    @DisplayName("get_sprint_risk reads stored scores and the board findings")
    void riskReadsLatest() {
        UUID sprintId = UUID.randomUUID();
        when(risk.latest(sprintId)).thenReturn(scored(sprintId));
        when(risk.adjustments(sprintId)).thenReturn(List.of());

        ToolOutcome outcome = registry.run("get_sprint_risk", "{\"sprintId\":\"" + sprintId + "\"}", ctx);

        assertThat(outcome.ok()).as(outcome.json()).isTrue();
        assertThat(outcome.json()).contains("boardFindings");
        verify(risk, never()).assess(sprintId);
    }

    @Test
    @DisplayName("get_sprint_risk scores a never-assessed sprint rather than reporting no risk")
    void riskFallsBackToAssess() {
        UUID sprintId = UUID.randomUUID();
        when(risk.latest(sprintId)).thenReturn(
                new SprintRiskResponse(sprintId, null, null, null, null, null));
        when(risk.assess(sprintId)).thenReturn(scored(sprintId));

        assertThat(registry.run("get_sprint_risk", "{\"sprintId\":\"" + sprintId + "\"}", ctx).ok()).isTrue();
        verify(risk).assess(sprintId);
    }

    @Test
    @DisplayName("rank_developers_for_task needs exactly one of taskId and storyId")
    void rankNeedsOneTarget() {
        UUID taskId = UUID.randomUUID();

        assertThat(registry.run("rank_developers_for_task", "{}", ctx).ok()).isFalse();
        assertThat(registry.run("rank_developers_for_task",
                "{\"taskId\":\"" + taskId + "\",\"storyId\":\"" + taskId + "\"}", ctx).ok()).isFalse();

        registry.run("rank_developers_for_task", "{\"taskId\":\"" + taskId + "\",\"limit\":50}", ctx);
        verify(allocation).rank(EntityType.TASK, taskId, null, 10);
    }

    @Test
    @DisplayName("forecast_sprint_cost requires a stage and passes it to M4")
    void cost() {
        UUID sprintId = UUID.randomUUID();
        assertThat(registry.run("forecast_sprint_cost", "{\"sprintId\":\"" + sprintId + "\"}", ctx).ok())
                .isFalse();

        registry.run("forecast_sprint_cost",
                "{\"sprintId\":\"" + sprintId + "\",\"stage\":\"MID_SPRINT\"}", ctx);
        verify(cost).forecast(sprintId, CostStage.MID_SPRINT);
    }

    @Test
    @DisplayName("search_work_items leaves tasks out for a user without task:read")
    void searchRespectsTaskPermission() {
        actingWith(Set.of("backlog:read", "ai:predict"));
        UUID projectId = UUID.randomUUID();
        when(stories.search(eq(projectId), any(), any(), any(), any(), any(), isNull(), anyBoolean(),
                any(PageQuery.class))).thenReturn(emptyPage());

        ToolOutcome outcome = registry.run("search_work_items", "{\"projectId\":\"" + projectId + "\"}", ctx);

        assertThat(outcome.ok()).as(outcome.json()).isTrue();
        assertThat(outcome.json()).contains("lacks task:read");
        verify(tasks, never()).search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any());
    }

    private static SprintRiskResponse scored(UUID sprintId) {
        OverallRiskResponse overall = new OverallRiskResponse(new BigDecimal("0.4"), RiskSeverity.MEDIUM,
                false, Map.of("overBudget", BigDecimal.ONE), "Overall 40% risk.");
        return new SprintRiskResponse(sprintId, null, null, null, RiskSeverity.MEDIUM, overall);
    }
}
