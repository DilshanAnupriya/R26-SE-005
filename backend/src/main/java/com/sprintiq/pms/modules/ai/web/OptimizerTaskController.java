package com.sprintiq.pms.modules.ai.web;

import com.sprintiq.pms.modules.ai.domain.Task;
import com.sprintiq.pms.modules.ai.domain.TaskStatus;
import com.sprintiq.pms.modules.ai.application.OptimizerTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@CrossOrigin("*")
public class OptimizerTaskController {

    @Autowired
    private OptimizerTaskService taskService;

    @PostMapping
    public Task addTask(@RequestBody Task task) {
        return taskService.addTask(task);
    }

    @GetMapping
    public List<Task> getAll() {
        return taskService.getAllTasks();
    }

    @GetMapping("/sprint/{sprintId}")
    public List<Task> getTasksBySprintId(@PathVariable Long sprintId) {
        return taskService.getTasksBySprintId(sprintId);
    }

    @GetMapping("/developer/{developerId}")
    public List<Task> getTasksByDeveloperId(@PathVariable Long developerId) {
        return taskService.getTasksByDeveloperId(developerId);
    }

    @PatchMapping("/{id}/status")
    public Task updateTaskStatus(@PathVariable Long id, @RequestParam TaskStatus status) {
        return taskService.updateTaskStatus(id, status);
    }

    @PostMapping("/sprint/{sprintId}/complete-all")
    public ResponseEntity<?> completeAllTasksInSprint(@PathVariable Long sprintId) {
        try {
            taskService.completeAllTasksInSprint(sprintId);
            return ResponseEntity.ok(Map.of("message", "All tasks marked as completed"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }
}
