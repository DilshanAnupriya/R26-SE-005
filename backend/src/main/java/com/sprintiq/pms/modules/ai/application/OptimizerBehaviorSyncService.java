package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.Sprint;
import com.sprintiq.pms.modules.ai.domain.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class OptimizerBehaviorSyncService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private OptimizerDeveloperService developerService;

    @Value("${python.api.url:http://localhost:8000/predict}")
    private String pythonApiUrl;

    public void syncBehaviorFromML(List<Map<String, Object>> sprintLogs) {
        if (sprintLogs == null || sprintLogs.isEmpty()) {
            System.out.println("No logs to sync.");
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("logs", sprintLogs);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        System.out.println("Sending " + sprintLogs.size() + " task logs to ML...");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            // Replace /predict with /behavior/predict-all in the URL
            String url = pythonApiUrl.replace("/predict", "/behavior/predict-all");
            if (url.equals(pythonApiUrl)) {
                url = pythonApiUrl.endsWith("/") ? pythonApiUrl + "behavior/predict-all" : pythonApiUrl + "/behavior/predict-all";
            }

            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            Map<String, Object> body = response.getBody();

            if (body != null && body.containsKey("developers")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("developers");
                System.out.println("Received metrics for " + results.size() + " developers from ML.");
                developerService.updateBehaviorMetrics(results);
            }
        } catch (Exception e) {
            System.err.println("Error syncing behavior from ML: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Extracts behavioral metrics from a completed sprint and sends them to ML.
     */
    public void syncSprintMetrics(Sprint sprint) {
        List<Map<String, Object>> logs = sprint.getTasks().stream()
                .filter(task -> task.getDeveloper() != null)
                .map(task -> {
                    Map<String, Object> log = new HashMap<>();
                    log.put("dev_id", task.getDeveloper().getDev_id());
                    log.put("sprint_id", sprint.getId());
                    log.put("completion_time", task.getCompletion_time());
                    log.put("defects", task.getDefects());
                    log.put("velocity_contribution", task.getVelocity_contribution());
                    log.put("blocked_tasks", task.isBlocked() ? 1 : 0);
                    log.put("reopened_tasks", task.isReopened() ? 1 : 0);
                    return log;
                })
                .collect(Collectors.toList());

        syncBehaviorFromML(logs);
    }
}
