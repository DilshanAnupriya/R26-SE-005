package com.sprintiq.pms.modules.copilot.application.tools;

import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.modules.backlog.application.SprintBacklogService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.ProjectOverviewResponse;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import com.sprintiq.pms.modules.tasks.application.TaskService;
import com.sprintiq.pms.security.PermissionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read access to the system itself: projects, sprints, stories and tasks.
 *
 * <p>These are how the model turns "the mobile app's current sprint" into an id the
 * model tools accept, so their descriptions say so.</p>
 */
@Configuration
public class DeliveryTools {

    /** Work items the model sees per search. Enough to answer; few enough to read. */
    static final int SEARCH_LIMIT = 25;

    enum WorkItemKind { STORY, TASK, ALL }

    static PageQuery page(String search, int size) {
        PageQuery query = new PageQuery();
        query.setQ(search);
        query.setSize(size);
        return query;
    }

    @Bean
    CopilotTool listProjectsTool(ProjectService projects) {
        return SimpleTool.builder()
                .name("list_projects")
                .label("Listed projects")
                .description("List projects in the organisation with status, health and progress. "
                        + "Use this first to find a project's id from its name.")
                .parameters(ToolSchemas.object(Map.of(
                        "query", ToolSchemas.string("Optional name filter")), List.of()))
                .requiredPermission("project:read")
                .handler((args, ctx) -> projects
                        .search(null, null, null, null, page(ToolArgs.optionalString(args, "query"), 50))
                        .content().stream().map(Projections::project).toList())
                .build();
    }

    @Bean
    CopilotTool projectOverviewTool(ProjectService projects) {
        return SimpleTool.builder()
                .name("get_project_overview")
                .label("Read project overview")
                .description("Counts for one project: stories, tasks, blocked items, points done, "
                        + "the active sprint, and the health signals behind its health badge.")
                .parameters(ToolSchemas.object(Map.of(
                        "projectId", ToolSchemas.uuid("Project id")), List.of("projectId")))
                .requiredPermission("project:read")
                .handler((args, ctx) -> {
                    UUID projectId = ToolArgs.requireUuid(args, "projectId");
                    ProjectOverviewResponse o = projects.overview(projectId);
                    Map<String, Object> m = new LinkedHashMap<>(Projections.project(o.project()));
                    m.put("epicCount", o.epicCount());
                    m.put("storyCount", o.storyCount());
                    m.put("completedStoryCount", o.completedStoryCount());
                    m.put("taskCount", o.taskCount());
                    m.put("openTaskCount", o.openTaskCount());
                    m.put("blockedCount", o.blockedCount());
                    m.put("totalStoryPoints", o.totalStoryPoints());
                    m.put("completedStoryPoints", o.completedStoryPoints());
                    m.put("activeSprintId", o.activeSprintId());
                    m.put("activeSprintName", o.activeSprintName());
                    m.put("healthSignals", projects.health(projectId).signals());
                    return m;
                })
                .build();
    }

    @Bean
    CopilotTool listSprintsTool(SprintService sprints) {
        return SimpleTool.builder()
                .name("list_sprints")
                .label("Listed sprints")
                .description("Sprints of a project, newest first, with dates, capacity, committed "
                        + "and completed points, budget and forecast cost.")
                .parameters(ToolSchemas.object(Map.of(
                        "projectId", ToolSchemas.uuid("Project id"),
                        "status", ToolSchemas.enumOf("Optional status filter", SprintStatus.values())),
                        List.of("projectId")))
                .requiredPermission("sprint:read")
                .handler((args, ctx) -> sprints
                        .search(ToolArgs.requireUuid(args, "projectId"),
                                ToolArgs.optionalEnum(args, "status", SprintStatus.class), page(null, 20))
                        .content().stream().map(Projections::sprint).toList())
                .build();
    }

    @Bean
    CopilotTool sprintSummaryTool(SprintService sprints, SprintBacklogService sprintBacklog) {
        return SimpleTool.builder()
                .name("get_sprint_summary")
                .label("Read sprint summary")
                .description("One sprint with its stories (key, title, status, points, assignee).")
                .parameters(ToolSchemas.object(Map.of(
                        "sprintId", ToolSchemas.uuid("Sprint id")), List.of("sprintId")))
                .requiredPermission("sprint:read")
                .handler((args, ctx) -> {
                    UUID sprintId = ToolArgs.requireUuid(args, "sprintId");
                    Map<String, Object> m = new LinkedHashMap<>(Projections.sprint(sprints.get(sprintId)));
                    m.put("stories", sprintBacklog.storiesIn(sprintId).stream()
                            .map(Projections::story).toList());
                    return m;
                })
                .build();
    }

    @Bean
    CopilotTool searchWorkItemsTool(StoryService stories, TaskService tasks, PermissionService perm) {
        return SimpleTool.builder()
                .name("search_work_items")
                .label("Searched work items")
                .description("Find stories and/or tasks in a project by text, optionally within one "
                        + "sprint. Returns ids, keys, status, points and assignees. At most "
                        + SEARCH_LIMIT + " of each kind.")
                .parameters(ToolSchemas.object(Map.of(
                        "projectId", ToolSchemas.uuid("Project id"),
                        "query", ToolSchemas.string("Optional text to match in title or key"),
                        "sprintId", ToolSchemas.uuid("Optional sprint to restrict to"),
                        "kind", ToolSchemas.enumOf("STORY, TASK or ALL (default ALL)",
                                WorkItemKind.values())),
                        List.of("projectId")))
                .requiredPermission("backlog:read")
                .handler((args, ctx) -> {
                    UUID projectId = ToolArgs.requireUuid(args, "projectId");
                    UUID sprintId = ToolArgs.optionalUuid(args, "sprintId");
                    String query = ToolArgs.optionalString(args, "query");
                    WorkItemKind kind = ToolArgs.optionalEnum(args, "kind", WorkItemKind.class);
                    if (kind == null) kind = WorkItemKind.ALL;

                    Map<String, Object> m = new LinkedHashMap<>();
                    if (kind != WorkItemKind.TASK) {
                        m.put("stories", stories.search(projectId, null, null, null, null, null,
                                        sprintId, false, page(query, SEARCH_LIMIT))
                                .content().stream().map(Projections::story).toList());
                    }
                    // Tasks have their own read permission; a user with backlog access
                    // alone gets stories and an honest note rather than a refusal.
                    if (kind != WorkItemKind.STORY && !perm.has("task:read")) {
                        m.put("tasks", "not visible: the user lacks task:read");
                    } else if (kind != WorkItemKind.STORY) {
                        m.put("tasks", tasks.search(projectId, null, sprintId, null, null, null, null,
                                        null, false, page(query, SEARCH_LIMIT))
                                .content().stream().map(Projections::task).toList());
                    }
                    return m;
                })
                .build();
    }
}
