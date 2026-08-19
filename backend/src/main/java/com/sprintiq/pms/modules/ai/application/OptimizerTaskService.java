package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.Task;
import com.sprintiq.pms.modules.ai.domain.TaskStatus;
import com.sprintiq.pms.modules.ai.domain.OptimizerTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OptimizerTaskService {

    @Autowired
    private OptimizerTaskRepository taskRepository;

    @Autowired
    private com.sprintiq.pms.modules.ai.domain.OptimizerSprintRepository sprintRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public Task addTask(Task task) {
        return taskRepository.save(task);
    }

    public List<Task> getAllTasks() {
        return taskRepository.findAll();
    }

    public List<Task> getTasksBySprintId(Long sprintId) {
        syncSprintStories(sprintId);
        return taskRepository.findBySprintId(sprintId);
    }

    public void syncSprintStories(Long sprintId) {
        try {
            com.sprintiq.pms.modules.ai.domain.Sprint optSprint = sprintRepository.findById(sprintId).orElse(null);
            if (optSprint == null) return;

            String sprintIdentifier = optSprint.getSprintId();

            List<Map<String, Object>> stories = jdbcTemplate.queryForList(
                    "SELECT st.id, st.item_key, st.title, st.description, st.acceptance_criteria, st.story_points, st.status, st.blocked, st.organization_id " +
                    "FROM stories st " +
                    "JOIN sprints s ON s.id = st.sprint_id " +
                    "JOIN projects p ON p.id = s.project_id " +
                    "WHERE (CONCAT(s.name, ' (', p.project_key, ')') = ? OR s.id::text = ? OR s.name = ?) AND st.deleted_at IS NULL",
                    sprintIdentifier, sprintIdentifier, sprintIdentifier
            );

            for (Map<String, Object> st : stories) {
                String itemKey = st.get("item_key") != null ? st.get("item_key").toString() : "";
                String title = st.get("title") != null ? st.get("title").toString() : "";
                String taskType = itemKey.isEmpty() ? title : itemKey + ": " + title;
                String desc = (st.get("description") != null ? st.get("description").toString() : "") + " " +
                              (st.get("acceptance_criteria") != null ? st.get("acceptance_criteria").toString() : "");

                Number spNum = (Number) st.get("story_points");
                int storyPoints = spNum != null ? Math.max(1, spNum.intValue()) : 3;
                int complexity = storyPoints >= 8 ? 5 : storyPoints >= 5 ? 4 : storyPoints >= 3 ? 3 : storyPoints >= 2 ? 2 : 1;
                boolean blocked = Boolean.TRUE.equals(st.get("blocked"));

                String text = (title + " " + desc).toLowerCase();
                int reqFrontend = 4;
                int reqBackend = 4;
                int reqDb = 3;

                if (text.contains("ui") || text.contains("ux") || text.contains("frontend") || text.contains("design") ||
                    text.contains("css") || text.contains("html") || text.contains("react") || text.contains("page") ||
                    text.contains("screen") || text.contains("view") || text.contains("component")) {
                    reqFrontend = 8;
                    reqBackend = Math.min(reqBackend, 3);
                    reqDb = Math.min(reqDb, 2);
                }
                if (text.contains("backend") || text.contains("api") || text.contains("oauth") || text.contains("auth") ||
                    text.contains("service") || text.contains("endpoint") || text.contains("rest") || text.contains("security") ||
                    text.contains("controller") || text.contains("server")) {
                    reqBackend = 9;
                    reqFrontend = text.contains("ui") ? 6 : 2;
                    reqDb = Math.max(reqDb, 5);
                }
                if (text.contains("database") || text.contains("db") || text.contains("sql") || text.contains("postgres") ||
                    text.contains("schema") || text.contains("migration") || text.contains("table") || text.contains("query") ||
                    text.contains("index") || text.contains("model")) {
                    reqDb = 9;
                    reqBackend = Math.max(reqBackend, 6);
                    reqFrontend = text.contains("ui") ? 4 : 1;
                }

                List<Task> existingTasks = taskRepository.findBySprintId(sprintId);
                boolean exists = existingTasks.stream().anyMatch(t -> t.getTask_type() != null && t.getTask_type().equals(taskType));

                if (!exists) {
                    Task newTask = new Task();
                    newTask.setSprint(optSprint);
                    newTask.setTask_type(taskType);
                    newTask.setStory_points(storyPoints);
                    newTask.setTask_complexity(complexity);
                    newTask.setReq_frontend(reqFrontend);
                    newTask.setReq_backend(reqBackend);
                    newTask.setReq_db(reqDb);
                    newTask.setAmbiguity(0.2);
                    newTask.setDependency_risk(blocked ? 0.8 : 0.1);
                    newTask.setBlocked(blocked);
                    newTask.setReopened(false);
                    newTask.setStatus(TaskStatus.TODO);
                    newTask.setOrganizationId(optSprint.getOrganizationId());
                    taskRepository.save(newTask);
                }
            }
        } catch (Exception e) {
            System.err.println("Note: Core stories sync: " + e.getMessage());
        }
    }

    public List<Task> getTasksByDeveloperId(Long developerId) {
        return taskRepository.findByDeveloperId(developerId);
    }

    // =====================================================
    // BUILD ML SPRINT LOGS
    // =====================================================
    public List<Map<String, Object>> buildSprintLogs() {
        List<Task> tasks = taskRepository.findAll();
        List<Map<String, Object>> logs = new ArrayList<>();

        for (Task task : tasks) {
            if (task.getDeveloper() == null || task.getSprint() == null) {
                continue; // Skip unassigned or unsprinted tasks
            }

            Map<String, Object> row = new HashMap<>();
            row.put("dev_id", task.getDeveloper().getDev_id());
            row.put("sprint_id", task.getSprint().getId());
            row.put("completion_time", task.getCompletion_time());
            row.put("defects", task.getDefects());
            row.put("velocity_contribution", task.getVelocity_contribution());
            row.put("blocked_tasks", task.isBlocked() ? 1 : 0);
            row.put("reopened_tasks", task.isReopened() ? 1 : 0);
            logs.add(row);
        }

        return logs;
    }

    public Task updateTaskStatus(Long id, TaskStatus status) {
        Task task = taskRepository.findById(id).orElseThrow(() -> new RuntimeException("Task not found"));
        task.setStatus(status);
        if (status == TaskStatus.COMPLETED) {
            task.setCompleted_at(java.time.LocalDateTime.now());
        } else if (status == TaskStatus.IN_PROGRESS && task.getStarted_at() == null) {
            task.setStarted_at(java.time.LocalDateTime.now());
        }
        return taskRepository.save(task);
    }

    public void completeAllTasksInSprint(Long sprintId) {
        List<Task> tasks = taskRepository.findBySprintId(sprintId);
        for (Task task : tasks) {
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompleted_at(java.time.LocalDateTime.now());
        }
        taskRepository.saveAll(tasks);
    }
}
