package com.sprintiq.pms.modules.ai.web;

import com.sprintiq.pms.modules.ai.domain.Allocation;
import com.sprintiq.pms.modules.ai.application.OptimizerAllocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/allocation")
@CrossOrigin("*")
public class OptimizerAllocationController {

    @Autowired
    private OptimizerAllocationService allocationService;

    @GetMapping("/{id}")
    public Allocation getAllocationById(@PathVariable Long id) {
        return allocationService.getAllocationById(id);
    }

    @GetMapping("/sprint/{id}")
    public List<Allocation> getAllocationsBySprint(@PathVariable Long id) {
        return allocationService.getAllocationsBySprint(id);
    }

    @GetMapping("/task/{taskId}")
    public List<Allocation> getAllocationsByTask(@PathVariable Long taskId) {
        return allocationService.getTaskAllocations(taskId);
    }

    @PutMapping("/assign")
    public Allocation assignDeveloper(
            @RequestParam Long allocationId,
            @RequestParam Long developerId) {
        return allocationService.assignRecommendedDeveloper(allocationId, developerId);
    }

    @PutMapping("/{allocationId}/decline")
    public Allocation declineAllocation(@PathVariable Long allocationId) {
        return allocationService.declineAllocation(allocationId);
    }

    @PostMapping("/approve-all/sprint/{sprintId}")
    public void approveAllRecommendations(@PathVariable Long sprintId) {
        allocationService.approveAllRecommendations(sprintId);
    }
}
