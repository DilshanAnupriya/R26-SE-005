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

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public List<Sprint> getAllSprints() {
        syncFromCoreSprints();
        return sprintRepository.findAll();
    }

    public Sprint createSprint(Sprint sprint) {
        return sprintRepository.save(sprint);
    }

    public void syncFromCoreSprints() {
        try {
            List<java.util.Map<String, Object>> coreSprints = jdbcTemplate.queryForList(
                    "SELECT s.id, s.name, s.status, s.start_date, s.end_date, s.organization_id, p.project_key " +
                    "FROM sprints s JOIN projects p ON p.id = s.project_id " +
                    "WHERE s.deleted_at IS NULL"
            );
            for (java.util.Map<String, Object> cs : coreSprints) {
                String sprintName = cs.get("name") != null ? cs.get("name").toString() : "Sprint";
                String projKey = cs.get("project_key") != null ? cs.get("project_key").toString() : "";
                String displayId = sprintName + (!projKey.isEmpty() ? " (" + projKey + ")" : "");
                java.util.UUID orgId = cs.get("organization_id") != null ? (java.util.UUID) cs.get("organization_id") : null;
                String status = cs.get("status") != null ? cs.get("status").toString() : "PLANNED";
                java.sql.Date startD = (java.sql.Date) cs.get("start_date");
                java.sql.Date endD = (java.sql.Date) cs.get("end_date");

                java.util.Optional<Sprint> existing = sprintRepository.findBySprintId(displayId);
                if (existing.isEmpty()) {
                    existing = sprintRepository.findBySprintId(sprintName);
                }
                if (existing.isEmpty()) {
                    Sprint newSprint = Sprint.builder()
                            .sprintId(displayId)
                            .status(status)
                            .startDate(startD != null ? startD.toLocalDate() : null)
                            .endDate(endD != null ? endD.toLocalDate() : null)
                            .organizationId(orgId)
                            .build();
                    sprintRepository.save(newSprint);
                } else {
                    Sprint s = existing.get();
                    s.setStatus(status);
                    s.setSprintId(displayId);
                    sprintRepository.save(s);
                }
            }
        } catch (Exception e) {
            System.err.println("Note: Core sprint sync: " + e.getMessage());
        }
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
