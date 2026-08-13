package com.sprintiq.pms.modules.ai.web;

import com.sprintiq.pms.modules.ai.web.dto.OptimizerDtos.TaskRequest;
import com.sprintiq.pms.modules.ai.application.OptimizerRankingService;
import com.sprintiq.pms.modules.ai.application.OptimizerBehaviorSyncService;
import com.sprintiq.pms.modules.ai.application.OptimizerTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rank")
@CrossOrigin("*")
public class OptimizerRankingController {

    @Autowired
    private OptimizerRankingService rankingService;

    @Autowired
    private OptimizerBehaviorSyncService behaviorSyncService;

    @Autowired
    private OptimizerTaskService taskService;

    @PostMapping
    public List<Map<String, Object>> rankDevelopers(@RequestBody TaskRequest taskRequest) {
        return rankingService.rankDevelopers(taskRequest);
    }

    @PostMapping("/sprint/{id}")
    public List<Map<String, Object>> allocateSprint(@PathVariable Long id) {
        return rankingService.allocateSprint(id);
    }

    @PostMapping("/recommendations/sprint/{id}")
    public List<Map<String, Object>> getSprintRecommendations(@PathVariable Long id) {
        return rankingService.getSprintRecommendations(id);
    }

    @PostMapping("/explain/{allocationId}")
    public Map<String, Object> explainAllocation(@PathVariable Long allocationId) {
        return rankingService.explainAllocation(allocationId);
    }

    @PostMapping("/sync-behavior")
    public String syncBehavior() {
        List<Map<String, Object>> logs = taskService.buildSprintLogs();
        behaviorSyncService.syncBehaviorFromML(logs);
        return "Behavior sync completed";
    }
}
