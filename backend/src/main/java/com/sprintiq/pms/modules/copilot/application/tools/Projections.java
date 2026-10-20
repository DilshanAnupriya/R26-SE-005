package com.sprintiq.pms.modules.copilot.application.tools;

import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.ProjectResponse;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.SprintResponse;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.TaskResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fields of each entity the model actually reasons with.
 *
 * <p>Full responses carry avatars, checklists, ranks and timestamps — tokens that cost
 * money on every call and give the model more material to wander off with. Ids stay,
 * because the next tool call needs them.</p>
 */
final class Projections {

    private Projections() {}

    static Map<String, Object> project(ProjectResponse p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("key", p.projectKey());
        m.put("name", p.name());
        m.put("status", p.status());
        m.put("health", p.health());
        m.put("progressPct", p.progressPct());
        m.put("targetDate", p.targetDate());
        m.put("budget", p.budgetAmount());
        m.put("currency", p.currency());
        m.put("teamVelocityAvg", p.teamVelocityAvg());
        m.put("memberCount", p.memberCount());
        return m;
    }

    static Map<String, Object> sprint(SprintResponse s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("projectId", s.projectId());
        m.put("name", s.name());
        m.put("goal", s.goal());
        m.put("status", s.status());
        m.put("startDate", s.startDate());
        m.put("endDate", s.endDate());
        m.put("daysRemaining", s.daysRemaining());
        m.put("capacityPoints", s.capacityPoints());
        m.put("committedPoints", s.committedPoints());
        m.put("completedPoints", s.completedPoints());
        m.put("addedPoints", s.addedPoints());
        m.put("removedPoints", s.removedPoints());
        m.put("completionRate", s.completionRate());
        m.put("budget", s.budgetAmount());
        m.put("forecastCost", s.forecastCostAmount());
        m.put("storyCount", s.storyCount());
        m.put("completedStoryCount", s.completedStoryCount());
        return m;
    }

    static Map<String, Object> story(StoryResponse s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("kind", "STORY");
        m.put("key", s.itemKey());
        m.put("title", s.title());
        m.put("status", s.status());
        m.put("priority", s.priority());
        m.put("storyPoints", s.storyPoints());
        m.put("aiSuggestedPoints", s.aiEstimate() == null ? null : s.aiEstimate().points());
        m.put("sprintId", s.sprintId());
        m.put("assigneeId", s.assigneeId());
        m.put("assignee", s.assigneeName());
        m.put("blocked", s.blocked());
        return m;
    }

    static Map<String, Object> task(TaskResponse t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("kind", "TASK");
        m.put("key", t.itemKey());
        m.put("title", t.title());
        m.put("status", t.status());
        m.put("priority", t.priority());
        m.put("storyId", t.storyId());
        m.put("sprintId", t.sprintId());
        m.put("assigneeId", t.assigneeId());
        m.put("assignee", t.assigneeName());
        m.put("estimateMinutes", t.estimateMinutes());
        m.put("spentMinutes", t.spentMinutes());
        m.put("dueDate", t.dueDate());
        m.put("blocked", t.blocked());
        return m;
    }
}
