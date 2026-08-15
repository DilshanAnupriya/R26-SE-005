package com.sprintiq.pms.modules.ai.web;

import com.sprintiq.pms.modules.ai.domain.Developer;
import com.sprintiq.pms.modules.ai.domain.Task;
import com.sprintiq.pms.modules.ai.domain.TaskStatus;
import com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperRepository;
import com.sprintiq.pms.modules.ai.domain.OptimizerTaskRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/api/simulator")
@CrossOrigin("*")
public class OptimizerSimulatorController {

    @Autowired
    private OptimizerDeveloperRepository developerRepository;

    @Autowired
    private OptimizerTaskRepository taskRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${python.api.url:http://localhost:8000/predict}")
    private String pythonApiUrl;

    @PostMapping("/calculate")
    public SimulationResponse calculate(@RequestBody List<SimulationAssignment> assignments) {
        double totalStoryPoints = 0;
        double predictedVelocity = 0;
        int assignmentsCount = 0;

        for (SimulationAssignment assignment : assignments) {
            Optional<Developer> devOpt = developerRepository.findById(assignment.getDeveloperId());
            Optional<Task> taskOpt = taskRepository.findById(assignment.getTaskId());
            
            if (devOpt.isPresent() && taskOpt.isPresent()) {
                Developer dev = devOpt.get();
                Task task = taskOpt.get();
                
                double sp = task.getStory_points();
                totalStoryPoints += sp;
                assignmentsCount++;

                // LIVE ML INFERENCE CALL
                try {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("task", task);
                    payload.put("developers", Collections.singletonList(dev));

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

                    // Call the Python ML service (/predict)
                    ResponseEntity<Map> mlResponse = restTemplate.postForEntity(pythonApiUrl, entity, Map.class);
                    
                    if (mlResponse.getStatusCode() == HttpStatus.OK && mlResponse.getBody() != null) {
                        List<Map<String, Object>> recommendations = (List<Map<String, Object>>) mlResponse.getBody().get("recommendations");
                        if (recommendations != null && !recommendations.isEmpty()) {
                            double aiScore = Double.parseDouble(recommendations.get(0).get("final_score").toString());
                            predictedVelocity += (sp * (aiScore / 100.0));
                        }
                    }
                } catch (Exception e) {
                    // Fallback to basic logic if ML service is down
                    double performancePrediction = (dev.getConsistency() * 0.3 + (dev.getExperience_level() / 5.0) * 0.7);
                    predictedVelocity += (sp * performancePrediction);
                    System.err.println("ML Simulation Fallback: " + e.getMessage());
                }
            }
        }

        double healthScore = (totalStoryPoints > 0) ? (predictedVelocity / totalStoryPoints) * 100 : 0;

        SimulationResponse response = new SimulationResponse();
        response.setPredictedVelocity(Math.round(predictedVelocity * 100.0) / 100.0);
        response.setTotalStoryPoints(totalStoryPoints);
        response.setHealthScore(Math.round(healthScore * 10.0) / 10.0);
        response.setAssignmentCount(assignmentsCount);
        
        return response;
    }

    @PostMapping("/apply")
    public ResponseEntity<?> applyConfiguration(@RequestBody List<SimulationAssignment> assignments) {
        try {
            for (SimulationAssignment assignment : assignments) {
                Optional<Task> taskOpt = taskRepository.findById(assignment.getTaskId());
                Optional<Developer> devOpt = developerRepository.findById(assignment.getDeveloperId());
                
                if (taskOpt.isPresent() && devOpt.isPresent()) {
                    Task task = taskOpt.get();
                    task.setDeveloper(devOpt.get());
                    task.setStatus(TaskStatus.PLANNED);
                    taskRepository.save(task);
                }
            }
            return ResponseEntity.ok(Map.of("message", "Configuration applied successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/auto-optimize")
    public List<SimulationAssignment> autoOptimize(@RequestBody Map<String, Object> payload) {
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) payload.get("tasks");
        List<Object> developerIdsRaw = (List<Object>) payload.get("developerIds");
        Map<String, Object> currentAssignments = (Map<String, Object>) payload.get("currentAssignments");

        List<Long> developerIds = new ArrayList<>();
        if (developerIdsRaw != null) {
            for (Object idObj : developerIdsRaw) {
                developerIds.add(Long.valueOf(idObj.toString()));
            }
        }

        List<SimulationAssignment> newAssignments = new ArrayList<>();
        
        // Track current load for each developer (Manual + AI assigned)
        Map<Long, Double> devLoadMap = new HashMap<>();
        
        // Initialize with manual assignments
        if (currentAssignments != null) {
            currentAssignments.forEach((taskIdStr, devIdObj) -> {
                Optional<Task> tOpt = taskRepository.findById(Long.parseLong(taskIdStr));
                tOpt.ifPresent(task -> {
                    Long devId = Long.valueOf(devIdObj.toString());
                    devLoadMap.put(devId, devLoadMap.getOrDefault(devId, 0.0) + task.getStory_points());
                });
            });
        }

        // Convert tasks to a sorted list by complexity (assign harder tasks first)
        List<Map<String, Object>> mutableTasks = new ArrayList<>(tasks);
        mutableTasks.sort((a, b) -> Integer.compare(
            Integer.parseInt(b.get("storyPoints").toString()), 
            Integer.parseInt(a.get("storyPoints").toString())
        ));

        // For each unassigned task, find the best developer with capacity
        for (Map<String, Object> taskMap : mutableTasks) {
            Long taskId = Long.valueOf(taskMap.get("id").toString());
            
            // Skip if already manually assigned
            if (currentAssignments != null && currentAssignments.containsKey(taskId.toString())) continue;

            Long bestDevId = null;
            double bestScore = -1000.0; // Low baseline

            for (Long devId : developerIds) {
                Optional<Developer> devOpt = developerRepository.findById(devId);
                Optional<Task> taskOpt = taskRepository.findById(taskId);

                if (devOpt.isPresent() && taskOpt.isPresent()) {
                    Developer dev = devOpt.get();
                    Task task = taskOpt.get();
                    
                    // Calculate Dynamic Capacity based on Experience Level (1-5)
                    double dynamicCapacity = dev.getExperience_level() * 10.0; // Level 1=10SP, Level 5=50SP
                    if (dynamicCapacity == 0) dynamicCapacity = 20.0; // Fallback
                    
                    // HEURISTIC: (Skill Match * 0.5) + (Experience * 0.2) + (Consistency * 0.3)
                    double baseScore = (dev.getConsistency() * 0.3 + (dev.getExperience_level() / 5.0) * 0.7);
                    
                    // Workload Penalty: Grows as they reach their individual dynamic capacity
                    double currentLoad = devLoadMap.getOrDefault(devId, 0.0);
                    double workloadPenalty = (currentLoad / dynamicCapacity) * 1.5; 
                    
                    double finalScore = baseScore - workloadPenalty;

                    if (finalScore > bestScore) {
                        bestScore = finalScore;
                        bestDevId = devId;
                    }
                }
            }

            if (bestDevId != null) {
                SimulationAssignment sa = new SimulationAssignment();
                sa.setTaskId(taskId);
                sa.setDeveloperId(bestDevId);
                newAssignments.add(sa);
                
                // Update tracker
                Optional<Task> tOpt = taskRepository.findById(taskId);
                double sp = tOpt.map(Task::getStory_points).orElse(0);
                devLoadMap.put(bestDevId, devLoadMap.getOrDefault(bestDevId, 0.0) + sp);
            }
        }
        return newAssignments;
    }

    @Data
    public static class SimulationAssignment {
        private Long developerId;
        private Long taskId;
    }

    @Data
    public static class SimulationResponse {
        private double predictedVelocity;
        private double totalStoryPoints;
        private double healthScore;
        private int assignmentCount;
    }
}
