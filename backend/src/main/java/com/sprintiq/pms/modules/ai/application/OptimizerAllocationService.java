package com.sprintiq.pms.modules.ai.application;

import java.util.List;
import java.util.Optional;

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
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public Allocation getAllocationById(Long id) {
        return allocationRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Allocation not found with id: " + id));
    }

    public List<Allocation> getAllocationsBySprint(Long sprintId) {
        return allocationRepository.findBySprint_Id(sprintId);
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

        // 5. Also update core story assignee in PostgreSQL if task_type corresponds to an itemKey
        try {
            String taskType = task.getTask_type();
            if (taskType != null && taskType.contains(":")) {
                String itemKey = taskType.substring(0, taskType.indexOf(":")).trim();
                String devEmail = (developer.getProfile() != null && developer.getProfile().getEmail() != null) ? developer.getProfile().getEmail() : "";
                String devFullName = "";
                if (developer.getProfile() != null) {
                    String fn = developer.getProfile().getFirstName() != null ? developer.getProfile().getFirstName() : "";
                    String ln = developer.getProfile().getLastName() != null ? developer.getProfile().getLastName() : "";
                    devFullName = (fn + " " + ln).trim();
                }

                List<java.util.UUID> userIds = jdbcTemplate.query(
                        "SELECT u.id FROM users u " +
                        "WHERE (u.email IS NOT NULL AND LOWER(u.email) = LOWER(?)) " +
                        "   OR (u.full_name IS NOT NULL AND LOWER(u.full_name) = LOWER(?)) " +
                        "   OR (? != '' AND LOWER(u.full_name) LIKE LOWER(?)) LIMIT 1",
                        (rs, rowNum) -> (java.util.UUID) rs.getObject(1),
                        devEmail,
                        devFullName,
                        devFullName,
                        "%" + devFullName + "%"
                );
                if (userIds.isEmpty() && task.getOrganizationId() != null) {
                    userIds = jdbcTemplate.query(
                            "SELECT m.user_id FROM org_members m WHERE m.organization_id = ? LIMIT 1",
                            (rs, rowNum) -> (java.util.UUID) rs.getObject(1),
                            task.getOrganizationId()
                    );
                }
                if (!userIds.isEmpty()) {
                    java.util.UUID assigneeUid = userIds.get(0);
                    jdbcTemplate.update(
                            "UPDATE stories SET assignee_id = ?, updated_at = NOW() WHERE item_key = ?",
                            assigneeUid,
                            itemKey
                    );
                    try {
                        jdbcTemplate.update(
                                "INSERT INTO project_members (id, organization_id, project_id, user_id, project_role, is_stakeholder, allocation_pct, created_at, updated_at, version) " +
                                "SELECT gen_random_uuid(), s.organization_id, s.project_id, ?, 'DEVELOPER', false, 100, NOW(), NOW(), 0 " +
                                "FROM stories s WHERE s.item_key = ? " +
                                "ON CONFLICT DO NOTHING",
                                assigneeUid,
                                itemKey
                        );
                    } catch (Exception memberEx) {
                        // Project member insert fallback
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
        List<Allocation> allAllocations = allocationRepository.findBySprint_Id(sprintId);

        // Group by task to identify pending tasks
        java.util.Map<Long, List<Allocation>> taskGroups = allAllocations.stream()
            .collect(java.util.stream.Collectors.groupingBy(a -> a.getTask().getId()));

        for (java.util.Map.Entry<Long, List<Allocation>> entry : taskGroups.entrySet()) {
            List<Allocation> allocations = entry.getValue();

            // Skip if any developer is already ALLOCATED for this task
            boolean alreadyAllocated = allocations.stream()
                .anyMatch(a -> "ALLOCATED".equals(a.getStatus()));

            if (!alreadyAllocated) {
                // Find the Rank 1 recommendation
                allocations.stream()
                    .filter(a -> a.getRankPosition() == 1 && "RECOMMENDED".equals(a.getStatus()))
                    .findFirst()
                    .ifPresent(topAlloc -> {
                        assignRecommendedDeveloper(topAlloc.getId(), topAlloc.getDeveloper().getId());
                    });
            }
        }
    }
}
