package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.ai.web.dto.OptimizerDtos.TaskRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class OptimizerRankingService {

    @Autowired
    private OptimizerDeveloperRepository developerRepository;

    @Autowired
    private OptimizerTaskRepository taskRepository;

    @Autowired
    private OptimizerSprintRepository sprintRepository;

    @Autowired
    private OptimizerAllocationRepository allocationRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${python.api.url:http://localhost:8000/predict}")
    private String pythonApiUrl;

    public List<Map<String, Object>> rankDevelopers(TaskRequest taskRequest) {

        // 1. GET DEVELOPERS FROM POSTGRESQL
        List<Developer> developers = developerRepository.findAll();

        // 2. BUILD PAYLOAD
        Map<String, Object> payload = new HashMap<>();
        payload.put("task", taskRequest);
        payload.put("developers", developers);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        // 3. CALL ML SERVICE
        ResponseEntity<String> response = restTemplate.postForEntity(
                pythonApiUrl,
                request,
                String.class
        );

        // 4. PARSE RESPONSE
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> rankings = new ArrayList<>();
        try {
            rankings = mapper.readValue(response.getBody(), new TypeReference<>() {});
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }

        // 5. LOOKUP SPRINT AND TASK
        Optional<Sprint> sprintOpt = sprintRepository.findBySprintId(taskRequest.getSprintId());
        Optional<Task> taskOpt = taskRequest.getTaskId() != null ? 
                taskRepository.findById(taskRequest.getTaskId()) : Optional.empty();

        // 6. SAVE ALLOCATIONS
        for (Map<String, Object> rank : rankings) {
            String dev_id = rank.get("dev_id").toString();
            Optional<Developer> devOpt = developerRepository.findByDev_id(dev_id);

            if (devOpt.isPresent()) {
                Allocation allocation = Allocation.builder()
                        .developer(devOpt.get())
                        .sprint(sprintOpt.orElse(null))
                        .task(taskOpt.orElse(null))
                        .mlScore(Double.valueOf(rank.get("predicted_performance").toString()))
                        .skillMatchScore(Double.valueOf(rank.get("skill_match_score").toString()))
                        .workloadBalance(Double.valueOf(rank.get("workload_balance").toString()))
                        .finalScore(Double.valueOf(rank.get("final_score").toString()))
                        .rankPosition(Integer.valueOf(rank.get("rank").toString()))
                        .status("RECOMMENDED")
                        .explanation("AI Recommended")
                        .build();

                allocationRepository.save(allocation);
            }
        }
        return rankings;
    }

    @org.springframework.transaction.annotation.Transactional
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> allocateSprint(Long sprintId) {
        // 1. GET SPRINT AND TASKS
        Optional<Sprint> sprintOpt = sprintRepository.findById(sprintId);
        if (sprintOpt.isEmpty()) {
            throw new RuntimeException("Sprint not found with ID: " + sprintId);
        }
        Sprint sprint = sprintOpt.get();
        List<Task> tasks = taskRepository.findBySprintId(sprintId);
        List<Developer> developers = developerRepository.findAll();

        if (tasks == null || tasks.isEmpty()) {
            System.out.println("⚠️ No tasks found for sprint: " + sprintId);
            return Collections.emptyList();
        }

        // 2. BUILD PAYLOAD
        Map<String, Object> payload = new HashMap<>();
        payload.put("tasks", tasks);
        payload.put("developers", developers);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        // 3. CALL ML SERVICE (/allocate-sprint)
        String bulkUrl = pythonApiUrl.replace("/predict", "/allocate-sprint");
        if (bulkUrl.equals(pythonApiUrl)) {
            bulkUrl = pythonApiUrl.endsWith("/") ? pythonApiUrl + "allocate-sprint" : pythonApiUrl + "/allocate-sprint";
        }

        System.out.println("🚀 Calling ML Bulk Allocation at: " + bulkUrl);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(bulkUrl, request, Map.class);
            
            if (response.getStatusCode() != HttpStatus.OK) {
                throw new RuntimeException("ML Service returned error: " + response.getStatusCode());
            }

            Map<String, Object> responseBody = response.getBody();
            List<Map<String, Object>> allocationsRaw = (List<Map<String, Object>>) responseBody.get("allocations");

            // 4. SAVE ALLOCATIONS
            for (Map<String, Object> allocData : allocationsRaw) {
                Long taskId = Long.valueOf(allocData.get("task_id").toString());
                String devIdStr = allocData.get("dev_id").toString();
                
                Optional<Task> taskOpt = taskRepository.findById(taskId);
                Optional<Developer> devOpt = developerRepository.findByDev_id(devIdStr);

                if (taskOpt.isPresent() && devOpt.isPresent()) {
                    Allocation allocation = Allocation.builder()
                            .developer(devOpt.get())
                            .sprint(sprint)
                            .task(taskOpt.get())
                            .mlScore(0.0)
                            .finalScore(Double.valueOf(allocData.get("match_score").toString()))
                            .skillMatchScore(Double.valueOf(allocData.get("skill_match").toString()))
                            .status("AUTO_ALLOCATED")
                            .explanation("Bulk AI Recommendation")
                            .build();

                    allocationRepository.save(allocation);
                }
            }
            return allocationsRaw;

        } catch (Exception e) {
            System.err.println("❌ Error during bulk allocation: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to allocate sprint: " + e.getMessage(), e);
        }
    }

    //get recommendations for tasks in a sprint
    @org.springframework.transaction.annotation.Transactional
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSprintRecommendations(Long sprintId) {

        // 1. GET SPRINT AND TASKS
        Sprint sprint = sprintRepository.findById(sprintId)
                .orElseThrow(() -> new RuntimeException("Sprint not found with ID: " + sprintId));

        List<Task> allTasks = taskRepository.findBySprintId(sprintId);
        List<Task> tasks = allTasks.stream()
                .filter(t -> t.getDeveloper() == null)
                .toList();

        List<Developer> developers = developerRepository.findAll();

        if (tasks.isEmpty()) {
            System.out.println("⚠️ No unassigned tasks found for sprint " + sprintId + " (Total tasks in sprint: " + allTasks.size() + ")");
            return Collections.emptyList();
        }

        // 2. BUILD PAYLOAD
        Map<String, Object> payload = new HashMap<>();
        payload.put("tasks", tasks);
        payload.put("developers", developers);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        // 3. CALL ML SERVICE
        String rankUrl = pythonApiUrl.replace("/predict", "/rank-sprint");
        if (rankUrl.equals(pythonApiUrl)) {
            rankUrl = pythonApiUrl.endsWith("/") ? pythonApiUrl + "rank-sprint" : pythonApiUrl + "/rank-sprint";
        }

        System.out.println("🚀 Calling ML Service at: " + rankUrl + " with " + tasks.size() + " tasks and " + developers.size() + " developers");

        try {
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(rankUrl, request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("recommendations")) {
                System.err.println("⚠️ ML Service returned empty response or missing recommendations: " + responseBody);
                return Collections.emptyList();
            }

            List<Map<String, Object>> recommendations =
                    (List<Map<String, Object>>) responseBody.get("recommendations");

            // 4. SAVE WITHOUT DUPLICATES
            for (Map<String, Object> taskRec : recommendations) {

                Long taskId = Long.valueOf(taskRec.get("task_id").toString());

                Task task = taskRepository.findById(taskId)
                        .orElse(null);

                if (task == null) continue;

                List<Map<String, Object>> devRecs =
                        (List<Map<String, Object>>) taskRec.get("recommendations");

                if (devRecs == null) continue;

                for (Map<String, Object> devData : devRecs) {

                    String devId = devData.get("dev_id").toString();

                    Developer developer = developerRepository.findByDev_id(devId)
                            .orElse(null);

                    if (developer == null) continue;

                    Object explanationObj = devData.get("explanation");
                    String explanationJson = new ObjectMapper().writeValueAsString(explanationObj);

                    // ✅ DUPLICATE CHECK
                    boolean exists = allocationRepository.existsBySprintAndTaskAndDeveloper(sprint, task, developer);

                    if (exists) continue;

                    Allocation allocation = Allocation.builder()
                            .developer(developer)
                            .sprint(sprint)
                            .task(task)
                            .mlScore(Double.valueOf(devData.get("predicted_performance").toString()))
                            .skillMatchScore(Double.valueOf(devData.get("skill_match_score").toString()))
                            .workloadBalance(Double.valueOf(devData.get("workload_balance").toString()))
                            .finalScore(Double.valueOf(devData.get("final_score").toString()))
                            .rankPosition(Integer.valueOf(devData.get("rank").toString()))
                            .status("RECOMMENDED")
                            .explanation(explanationJson)
                            .build();

                    allocationRepository.save(allocation);
                }
            }

            System.out.println("✅ Successfully processed and saved recommendations for sprint " + sprintId);
            return recommendations;

        } catch (Exception e) {
            System.err.println("❌ Error calling ML Service at " + rankUrl + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to get sprint recommendations: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> explainAllocation(Long allocationId) {
        // 1. GET ALLOCATION
        Optional<Allocation> allocationOpt = allocationRepository.findById(allocationId);
        if (allocationOpt.isEmpty()) {
            throw new RuntimeException("Allocation not found for ID: " + allocationId);
        }
        Allocation allocation = allocationOpt.get();

        // 2. GET TASK, DEVELOPER, AND ALL DEVELOPERS
        Task task = allocation.getTask();
        Developer assignedDeveloper = allocation.getDeveloper();
        List<Developer> allDevelopers = developerRepository.findAll();

        if (task == null || assignedDeveloper == null) {
            throw new RuntimeException("Allocation is missing Task or Developer.");
        }

        // 3. BUILD PAYLOAD
        Map<String, Object> payload = new HashMap<>();
        payload.put("dev_id", assignedDeveloper.getDev_id());
        payload.put("task", task);
        payload.put("developers", allDevelopers);
        payload.put("predicted_performance", allocation.getMlScore());
        payload.put("skill_match_score", allocation.getSkillMatchScore());
        payload.put("workload_balance", allocation.getWorkloadBalance());
        payload.put("final_score", allocation.getFinalScore());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        // 4. CALL ML SERVICE (/explain)
        String explainUrl = pythonApiUrl.replace("/predict", "/explain");
        if (explainUrl.equals(pythonApiUrl)) {
            explainUrl = pythonApiUrl.endsWith("/") ? pythonApiUrl + "explain" : pythonApiUrl + "/explain";
        }

        System.out.println("Calling ML Explain Allocation at: " + explainUrl);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(explainUrl, request, Map.class);
            
            if (response.getStatusCode() != HttpStatus.OK) {
                throw new RuntimeException("ML Service returned error: " + response.getStatusCode());
            }

            return response.getBody();

        } catch (Exception e) {
            System.err.println("❌ Error during explanation generation: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to get explanation: " + e.getMessage());
        }
    }
}
