package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.Sprint;
import com.sprintiq.pms.modules.ai.domain.Task;
import com.sprintiq.pms.modules.ai.domain.Developer;
import com.sprintiq.pms.modules.ai.domain.TaskStatus;
import com.sprintiq.pms.modules.ai.domain.OptimizerSprintRepository;
import com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OptimizerSprintService {

    @Autowired
    private OptimizerSprintRepository sprintRepository;

    @Autowired
    private OptimizerBehaviorSyncService behaviorSyncService;

    @Autowired
    private OptimizerDeveloperRepository developerRepository;

    public List<Sprint> getAllSprints() {
        return sprintRepository.findAll();
    }

    public Sprint getSprintById(Long id) {
        return sprintRepository.findById(id).orElseThrow(() -> new RuntimeException("Sprint not found"));
    }

    public Sprint completeSprint(Long id) {
        Sprint sprint = sprintRepository.findById(id).orElseThrow(() -> new RuntimeException("Sprint not found"));

        // Check if all tasks are completed
        boolean allTasksCompleted = sprint.getTasks().stream()
                .allMatch(task -> TaskStatus.COMPLETED.equals(task.getStatus()));

        if (!allTasksCompleted) {
            throw new RuntimeException("Cannot complete sprint. Some tasks are still in progress.");
        }

        sprint.setStatus("COMPLETED");
        Sprint savedSprint = sprintRepository.save(sprint);

        // Update developer behavioral metrics (consistency, learning rate)
        try {
            behaviorSyncService.syncSprintMetrics(savedSprint);
        } catch (Exception e) {
            System.err.println("Warning: Failed to sync behavioral metrics: " + e.getMessage());
        }

        // Clear/Reduce developer workload and task count
        updateDeveloperWorkloads(savedSprint);

        return savedSprint;
    }

    private void updateDeveloperWorkloads(Sprint sprint) {
        for (Task task : sprint.getTasks()) {
            if (task.getDeveloper() != null) {
                Developer dev = task.getDeveloper();
                
                // Subtract task story points from workload and decrement task count
                int newWorkload = Math.max(0, dev.getCurrent_workload() - task.getStory_points());
                int newTaskCount = Math.max(0, dev.getCurrent_tasks() - 1);
                
                dev.setCurrent_workload(newWorkload);
                dev.setCurrent_tasks(newTaskCount);
                
                developerRepository.save(dev);
            }
        }
    }
}
