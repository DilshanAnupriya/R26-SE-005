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

    @Autowired
    private com.sprintiq.pms.modules.ai.domain.OptimizerAllocationRepository allocationRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @PostMapping("/apply")
    public ResponseEntity<?> applyConfiguration(@RequestBody List<SimulationAssignment> assignments) {
        try {
            for (SimulationAssignment assignment : assignments) {
                Optional<Task> taskOpt = taskRepository.findById(assignment.getTaskId());
                Optional<Developer> devOpt = developerRepository.findById(assignment.getDeveloperId());
                
                if (taskOpt.isPresent() && devOpt.isPresent()) {
                    Task task = taskOpt.get();
                    Developer dev = devOpt.get();
                    task.setDeveloper(dev);
                    task.setStatus(TaskStatus.PLANNED);
                    taskRepository.save(task);

                    List<com.sprintiq.pms.modules.ai.domain.Allocation> allocs = allocationRepository.findByTask_Id(task.getId());
                    for (com.sprintiq.pms.modules.ai.domain.Allocation a : allocs) {
                        if (a.getDeveloper().getId().equals(dev.getId())) {
                            a.setStatus("ALLOCATED");
                        } else if ("ALLOCATED".equals(a.getStatus())) {
                            a.setStatus("RECOMMENDED");
                        }
                        allocationRepository.save(a);
                    }

                    try {
                        String taskType = task.getTask_type();
                        if (taskType != null) {
                            String itemKey = taskType.contains(":") ? taskType.substring(0, taskType.indexOf(":")).trim() : taskType.trim();
                            String email = dev.getProfile() != null ? dev.getProfile().getEmail() : null;
                            if (email != null) {
                                jdbcTemplate.update(
                                    "UPDATE stories SET assignee_id = (SELECT id FROM users WHERE email = ? LIMIT 1), updated_at = NOW() WHERE item_key = ? OR title = ? OR CONCAT(item_key, ': ', title) = ?",
                                    email, itemKey, taskType, taskType
                                );
                            }
                        }
                    } catch (Exception ex) {
                        System.err.println("Note: Story sync in simulator apply: " + ex.getMessage());
                    }
                }
            }
            return ResponseEntity.ok(Map.of("message", "Configuration applied successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private double getDeveloperTaskScore(Task task, Developer dev) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("task", task);
            payload.put("developers", Collections.singletonList(dev));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            ResponseEntity<Map> mlResponse = restTemplate.postForEntity(pythonApiUrl, entity, Map.class);
            if (mlResponse.getStatusCode() == HttpStatus.OK && mlResponse.getBody() != null) {
                List<Map<String, Object>> recommendations = (List<Map<String, Object>>) mlResponse.getBody().get("recommendations");
                if (recommendations != null && !recommendations.isEmpty()) {
                    return Double.parseDouble(recommendations.get(0).get("final_score").toString());
                }
            }
        } catch (Exception e) {}

        // High accuracy skill & domain match fallback
        double totalReq = Math.max(1.0, task.getReq_frontend() + task.getReq_backend() + task.getReq_db());
        double skillMatch = (Math.min(dev.getSkill_frontend(), task.getReq_frontend())
                + Math.min(dev.getSkill_backend(), task.getReq_backend())
                + Math.min(dev.getSkill_db(), task.getReq_db())) / totalReq;

        double expScore = Math.min(1.0, dev.getExperience_level() / 5.0);
        double consistency = dev.getConsistency() > 0 ? dev.getConsistency() : 0.8;
        return (skillMatch * 60.0) + (expScore * 20.0) + (consistency * 20.0);
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

        if (developerIds.isEmpty() || tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }

        // Filter for tasks that are actually unassigned
        List<Task> unassignedTasks = new ArrayList<>();
        for (Map<String, Object> taskMap : tasks) {
            Long taskId = Long.valueOf(taskMap.get("id").toString());
            if (currentAssignments == null || !currentAssignments.containsKey(taskId.toString())) {
                taskRepository.findById(taskId).ifPresent(unassignedTasks::add);
            }
        }

        List<Developer> availableDevs = new ArrayList<>();
        for (Long devId : developerIds) {
            developerRepository.findById(devId).ifPresent(availableDevs::add);
        }

        if (unassignedTasks.isEmpty() || availableDevs.isEmpty()) {
            return Collections.emptyList();
        }

        // Precompute score matrix
        Map<Long, Map<Long, Double>> scoreMatrix = new HashMap<>();
        for (Task t : unassignedTasks) {
            scoreMatrix.put(t.getId(), new HashMap<>());
            for (Developer d : availableDevs) {
                scoreMatrix.get(t.getId()).put(d.getId(), getDeveloperTaskScore(t, d));
            }
        }

        // Optimal Global Bipartite Matching with capacity constraints
        int nTasks = unassignedTasks.size();
        int nDevs = availableDevs.size();
        int maxTasksPerDev = Math.max(1, (int) Math.ceil((double) nTasks / nDevs));

        Map<Long, Long> bestMatching = new HashMap<>();
        double[] maxTotalScore = new double[]{-1.0};

        findOptimalMatching(
            0,
            unassignedTasks,
            availableDevs,
            scoreMatrix,
            new HashMap<>(),
            new HashMap<>(),
            maxTasksPerDev,
            0.0,
            maxTotalScore,
            bestMatching
        );

        List<SimulationAssignment> result = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : bestMatching.entrySet()) {
            SimulationAssignment sa = new SimulationAssignment();
            sa.setTaskId(entry.getKey());
            sa.setDeveloperId(entry.getValue());
            result.add(sa);
        }

        return result;
    }

    private void findOptimalMatching(
        int taskIdx,
        List<Task> tasks,
        List<Developer> devs,
        Map<Long, Map<Long, Double>> scoreMatrix,
        Map<Long, Long> currentMatch,
        Map<Long, Integer> devAssignedCounts,
        int maxTasksPerDev,
        double currentScore,
        double[] bestScore,
        Map<Long, Long> bestMatch
    ) {
        if (taskIdx >= tasks.size()) {
            if (currentScore > bestScore[0]) {
                bestScore[0] = currentScore;
                bestMatch.clear();
                bestMatch.putAll(currentMatch);
            }
            return;
        }

        Task task = tasks.get(taskIdx);

        // Sort developers by score for this task descending
        List<Developer> sortedDevs = new ArrayList<>(devs);
        sortedDevs.sort((d1, d2) -> Double.compare(
            scoreMatrix.get(task.getId()).getOrDefault(d2.getId(), 0.0),
            scoreMatrix.get(task.getId()).getOrDefault(d1.getId(), 0.0)
        ));

        for (Developer dev : sortedDevs) {
            int currentDevCount = devAssignedCounts.getOrDefault(dev.getId(), 0);
            if (currentDevCount < maxTasksPerDev) {
                double score = scoreMatrix.get(task.getId()).getOrDefault(dev.getId(), 0.0);
                
                currentMatch.put(task.getId(), dev.getId());
                devAssignedCounts.put(dev.getId(), currentDevCount + 1);

                findOptimalMatching(
                    taskIdx + 1,
                    tasks,
                    devs,
                    scoreMatrix,
                    currentMatch,
                    devAssignedCounts,
                    maxTasksPerDev,
                    currentScore + score,
                    bestScore,
                    bestMatch
                );

                devAssignedCounts.put(dev.getId(), currentDevCount);
                currentMatch.remove(task.getId());
            }
        }
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
