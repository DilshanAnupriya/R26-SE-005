package com.sprintiq.pms.modules.ai.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.sprintiq.pms.modules.ai.domain.*;

@Service
public class OptimizerAllocationService {

    @Autowired
    private OptimizerAllocationRepository allocationRepository;

    @Autowired
    private OptimizerDeveloperRepository developerRepository;

    @Autowired
    private OptimizerTaskRepository taskRepository;

    @Autowired
    private OptimizerSprintRepository sprintRepository;

    @Autowired
    private OptimizerTaskService taskService;

    @Autowired(required = false)
    private OptimizerRankingService rankingService;

    @Autowired(required = false)
    private com.sprintiq.pms.modules.notifications.application.NotificationService notificationService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public Allocation getAllocationById(Long id) {
        return allocationRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Allocation not found with id: " + id));
    }

    public List<Allocation> getAllocationsBySprint(Long sprintId) {
        if (sprintId != null && taskService != null) {
            try {
                taskService.syncSprintStories(sprintId);
                List<Allocation> existing = allocationRepository.findBySprint_Id(sprintId);
                if (existing.isEmpty()) {
                    if (rankingService != null) {
                        try {
                            rankingService.getSprintRecommendations(sprintId);
                        } catch (Exception mlEx) {
                            generateMissingAllocationsForSprint(sprintId);
                        }
                    } else {
                        generateMissingAllocationsForSprint(sprintId);
                    }
                }
            } catch (Exception e) {
                System.err.println("Note: syncSprintStories/allocations error: " + e.getMessage());
            }
        }
        return allocationRepository.findBySprint_Id(sprintId);
    }

    private void generateMissingAllocationsForSprint(Long sprintId) {
        Sprint sprint = sprintRepository.findById(sprintId).orElse(null);
        if (sprint == null) return;

        List<Developer> allDevs = developerRepository.findAll();
        List<Developer> developers = (sprint.getOrganizationId() != null)
                ? allDevs.stream().filter(d -> sprint.getOrganizationId().equals(d.getOrganizationId())).toList()
                : allDevs;
        if (developers.isEmpty()) {
            developers = allDevs;
        }
        if (developers.isEmpty() || sprintTasks.isEmpty()) return;

        for (Task task : sprintTasks) {
            List<Allocation> existingAllocs = allocationRepository.findByTask_Id(task.getId());
            if (existingAllocs.isEmpty()) {
                List<CandidateScore> scores = new ArrayList<>();
                double totalReq = Math.max(1.0, task.getReq_frontend() + task.getReq_backend() + task.getReq_db());

                for (Developer dev : developers) {
                    double skillMatch = (Math.min(dev.getSkill_frontend(), task.getReq_frontend())
                            + Math.min(dev.getSkill_backend(), task.getReq_backend())
                            + Math.min(dev.getSkill_db(), task.getReq_db())) / totalReq;

                    double workloadFactor = Math.max(0.0, Math.min(1.0, dev.getAvailability() / 100.0));
                    double mlScore = Math.max(0.5, Math.min(1.0, dev.getConsistency() > 0 ? dev.getConsistency() : 0.8));

                    double finalScore = Math.min(99.0, Math.max(40.0,
                            (skillMatch * 45.0) + (workloadFactor * 35.0) + (mlScore * 20.0)));

                    scores.add(new CandidateScore(dev, finalScore, skillMatch, workloadFactor, mlScore));
                }

                scores.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));

                for (int i = 0; i < scores.size(); i++) {
                    CandidateScore cs = scores.get(i);
                    Allocation alloc = new Allocation();
                    alloc.setSprint(sprint);
                    alloc.setTask(task);
                    alloc.setDeveloper(cs.developer);
                    alloc.setRankPosition(i + 1);
                    alloc.setFinalScore(cs.finalScore);
                    alloc.setSkillMatchScore(cs.skillMatch);
                    alloc.setWorkloadBalance(cs.workloadFactor);
                    alloc.setMlScore(cs.mlScore);

                    if (task.getDeveloper() != null && task.getDeveloper().getId().equals(cs.developer.getId())) {
                        alloc.setStatus("ALLOCATED");
                    } else {
                        alloc.setStatus("RECOMMENDED");
                    }

                    alloc.setOrganizationId(sprint.getOrganizationId());
                    allocationRepository.save(alloc);
                }
            }
        }
    }

    private static class CandidateScore {
        Developer developer;
        double finalScore;
        double skillMatch;
        double workloadFactor;
        double mlScore;

        CandidateScore(Developer d, double f, double s, double w, double m) {
            this.developer = d;
            this.finalScore = f;
            this.skillMatch = s;
            this.workloadFactor = w;
            this.mlScore = m;
        }
    }

    public List<Allocation> getTaskAllocations(Long taskId) {
        return allocationRepository.findByTask_Id(taskId);
    }

    public Optional<Allocation> updateAllocation(Long allocationId, Allocation allocationDetails) {
        return allocationRepository.findById(allocationId).map(allocation -> {
            allocation.setStatus(allocationDetails.getStatus());
            return allocationRepository.save(allocation);
        });
    }

    public Allocation assignRecommendedDeveloper(Long allocationId, Long developerId) {

        Allocation allocation = allocationRepository.findById(allocationId)
            .orElseThrow(() -> new RuntimeException("Allocation not found"));

        Developer developer = developerRepository.findById(developerId)
            .orElseThrow(() -> new RuntimeException("Developer not found"));

        Task task = allocation.getTask();

        // 1. Assign chosen developer to this allocation
        allocation.setDeveloper(developer);
        allocation.setStatus("ALLOCATED");

        // 2. Decline ONLY the other RECOMMENDED allocations for THIS same task
        List<Allocation> siblingAllocations = allocationRepository.findByTask_Id(task.getId());
        for (Allocation sibling : siblingAllocations) {
            if (!sibling.getId().equals(allocationId) && "RECOMMENDED".equals(sibling.getStatus())) {
                sibling.setStatus("DECLINED");
                allocationRepository.save(sibling);
            }
        }

        // 3. Assign developer to the task entity and set status to PLANNED
        task.setDeveloper(developer);
        task.setStatus(TaskStatus.PLANNED);
        taskRepository.save(task);

        // 4. Update developer's workload metrics
        int storyPoints = task.getStory_points();
        developer.setCurrent_tasks(developer.getCurrent_tasks() + 1);
        developer.setCurrent_workload(developer.getCurrent_workload() + storyPoints);
        double newAvailability = Math.max(0.0, developer.getAvailability() - (storyPoints * 5.0));
        developer.setAvailability(newAvailability);
        developerRepository.save(developer);

        // 5. Also update core story assignee in PostgreSQL if task_type corresponds to an itemKey or title
        try {
            String taskType = task.getTask_type();
            if (taskType != null) {
                String itemKey = taskType.contains(":") ? taskType.substring(0, taskType.indexOf(":")).trim() : taskType.trim();
                String storyTitle = taskType.contains(":") ? taskType.substring(taskType.indexOf(":") + 1).trim() : taskType.trim();

                String devEmail = (developer.getProfile() != null && developer.getProfile().getEmail() != null) ? developer.getProfile().getEmail() : "";
                String devFullName = "";
                if (developer.getProfile() != null) {
                    String fn = developer.getProfile().getFirstName() != null ? developer.getProfile().getFirstName() : "";
                    String ln = developer.getProfile().getLastName() != null ? developer.getProfile().getLastName() : "";
                    devFullName = (fn + " " + ln).trim();
                }

                List<UUID> userIds = jdbcTemplate.query(
                        "SELECT u.id FROM users u " +
                        "WHERE (u.email IS NOT NULL AND LOWER(u.email) = LOWER(?)) " +
                        "   OR (u.full_name IS NOT NULL AND LOWER(u.full_name) = LOWER(?)) " +
                        "   OR (? != '' AND LOWER(u.full_name) LIKE LOWER(?)) " +
                        "   OR (LOWER(u.email_normalized) = LOWER(?)) LIMIT 1",
                        (rs, rowNum) -> (UUID) rs.getObject(1),
                        devEmail,
                        devFullName,
                        devFullName,
                        "%" + devFullName + "%",
                        developer.getDev_id() != null ? developer.getDev_id().trim() : ""
                );

                if (!userIds.isEmpty()) {
                    UUID assigneeUid = userIds.get(0);
                    jdbcTemplate.update(
                            "UPDATE stories SET assignee_id = ?, updated_at = NOW() WHERE item_key = ? OR title = ? OR title = ? OR CONCAT(item_key, ': ', title) = ?",
                            assigneeUid,
                            itemKey,
                            storyTitle,
                            taskType,
                            taskType
                    );
                    try {
                        jdbcTemplate.update(
                                "INSERT INTO project_members (id, organization_id, project_id, user_id, project_role, is_stakeholder, allocation_pct, created_at, updated_at, version) " +
                                "SELECT gen_random_uuid(), s.organization_id, s.project_id, ?, 'DEVELOPER', false, 100, NOW(), NOW(), 0 " +
                                "FROM stories s WHERE s.item_key = ? OR s.title = ? OR s.title = ? " +
                                "ON CONFLICT DO NOTHING",
                                assigneeUid,
                                itemKey,
                                storyTitle,
                                taskType
                        );
                    } catch (Exception memberEx) {}

                    if (notificationService != null) {
                        try {
                            notificationService.notify(new com.sprintiq.pms.modules.notifications.application.NotificationService.Notify(
                                    assigneeUid,
                                    com.sprintiq.pms.modules.notifications.domain.NotificationType.ASSIGNED,
                                    "%s was allocated to you by AI Optimizer".formatted(itemKey),
                                    storyTitle != null ? storyTitle : taskType,
                                    com.sprintiq.pms.common.domain.EntityType.STORY,
                                    null,
                                    "/projects"
                            ));
                        } catch (Exception notifEx) {
                            System.err.println("Note: AI allocation notification: " + notifEx.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Note: Core story assignee update: " + e.getMessage());
        }

        // 6. Persist and return the approved allocation
        return allocationRepository.save(allocation);
    }

    public Allocation declineAllocation(Long allocationId) {
        Allocation allocation = allocationRepository.findById(allocationId)
            .orElseThrow(() -> new RuntimeException("Allocation not found"));

        if ("ALLOCATED".equals(allocation.getStatus())) {
            throw new RuntimeException("Cannot decline an already-approved allocation.");
        }

        allocation.setStatus("DECLINED");
        return allocationRepository.save(allocation);
    }

    public void approveAllRecommendations(Long sprintId) {
        List<Allocation> allAllocations = getAllocationsBySprint(sprintId);

        // Group by task to identify pending tasks
        Map<Long, List<Allocation>> taskGroups = allAllocations.stream()
            .collect(java.util.stream.Collectors.groupingBy(a -> a.getTask().getId()));

        Set<Long> assignedDevIdsInSprint = new HashSet<>();

        // Identify devs already assigned to any task in this sprint
        for (List<Allocation> allocs : taskGroups.values()) {
            allocs.stream()
                .filter(a -> "ALLOCATED".equals(a.getStatus()))
                .findFirst()
                .ifPresent(a -> assignedDevIdsInSprint.add(a.getDeveloper().getId()));
        }

        for (Map.Entry<Long, List<Allocation>> entry : taskGroups.entrySet()) {
            List<Allocation> allocations = entry.getValue();

            // Skip if any developer is already ALLOCATED for this task
            boolean alreadyAllocated = allocations.stream()
                .anyMatch(a -> "ALLOCATED".equals(a.getStatus()));

            if (!alreadyAllocated) {
                // Sort allocations by rank
                allocations.sort((a, b) -> Integer.compare(a.getRankPosition(), b.getRankPosition()));

                // 1. Try to find top candidate who doesn't have a task in this sprint yet (fair distribution)
                Allocation chosen = allocations.stream()
                    .filter(a -> !assignedDevIdsInSprint.contains(a.getDeveloper().getId()) && "RECOMMENDED".equals(a.getStatus()))
                    .findFirst()
                    .orElseGet(() -> allocations.stream()
                        .filter(a -> "RECOMMENDED".equals(a.getStatus()))
                        .findFirst()
                        .orElse(null));

                if (chosen != null) {
                    assignRecommendedDeveloper(chosen.getId(), chosen.getDeveloper().getId());
                    assignedDevIdsInSprint.add(chosen.getDeveloper().getId());
                }
            }
        }
    }

    public void unassignAllInSprint(Long sprintId) {
        List<Allocation> sprintAllocs = allocationRepository.findBySprint_Id(sprintId);
        for (Allocation alloc : sprintAllocs) {
            alloc.setStatus("RECOMMENDED");
            allocationRepository.save(alloc);
        }

        List<Task> sprintTasks = taskRepository.findBySprintId(sprintId);
        for (Task task : sprintTasks) {
            task.setDeveloper(null);
            task.setStatus(TaskStatus.PLANNED);
            taskRepository.save(task);

            try {
                String taskType = task.getTask_type();
                if (taskType != null) {
                    String itemKey = taskType.contains(":") ? taskType.substring(0, taskType.indexOf(":")).trim() : taskType.trim();
                    jdbcTemplate.update("UPDATE stories SET assignee_id = NULL, updated_at = NOW() WHERE item_key = ? OR title = ? OR CONCAT(item_key, ': ', title) = ?", itemKey, taskType, taskType);
                }
            } catch (Exception e) {
                System.err.println("Note: unassign story update: " + e.getMessage());
            }
        }

        List<Developer> devs = developerRepository.findAll();
        for (Developer d : devs) {
            d.setCurrent_tasks(0);
            d.setCurrent_workload(0);
            d.setAvailability(100.0);
            developerRepository.save(d);
        }
    }

    public void unassignTask(Long taskId) {
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        Developer dev = task.getDeveloper();
        if (dev != null) {
            int sp = task.getStory_points();
            dev.setCurrent_tasks(Math.max(0, dev.getCurrent_tasks() - 1));
            dev.setCurrent_workload(Math.max(0, dev.getCurrent_workload() - sp));
            dev.setAvailability(Math.min(100.0, dev.getAvailability() + (sp * 5.0)));
            developerRepository.save(dev);
        }

        task.setDeveloper(null);
        task.setStatus(TaskStatus.PLANNED);
        taskRepository.save(task);

        List<Allocation> allocs = allocationRepository.findByTask_Id(taskId);
        for (Allocation a : allocs) {
            a.setStatus("RECOMMENDED");
            allocationRepository.save(a);
        }

        try {
            String taskType = task.getTask_type();
            if (taskType != null) {
                String itemKey = taskType.contains(":") ? taskType.substring(0, taskType.indexOf(":")).trim() : taskType.trim();
                jdbcTemplate.update("UPDATE stories SET assignee_id = NULL, updated_at = NOW() WHERE item_key = ? OR title = ? OR CONCAT(item_key, ': ', title) = ?", itemKey, taskType, taskType);
            }
        } catch (Exception e) {}
    }
}
