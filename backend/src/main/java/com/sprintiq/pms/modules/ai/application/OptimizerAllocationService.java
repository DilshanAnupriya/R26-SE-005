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

        // 5. Persist and return the approved allocation
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
