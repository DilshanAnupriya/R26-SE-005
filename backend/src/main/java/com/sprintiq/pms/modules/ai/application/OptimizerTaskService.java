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

    public Task addTask(Task task) {
        return taskRepository.save(task);
    }

    public List<Task> getAllTasks() {
        return taskRepository.findAll();
    }

    public List<Task> getTasksBySprintId(Long sprintId) {
        return taskRepository.findBySprintId(sprintId);
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
