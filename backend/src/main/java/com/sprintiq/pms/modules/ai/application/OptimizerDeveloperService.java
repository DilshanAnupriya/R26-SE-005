package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.Developer;
import com.sprintiq.pms.modules.ai.domain.DeveloperProfile;
import com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperProfileRepository;
import com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperRepository;
import com.sprintiq.pms.modules.ai.web.dto.OptimizerDtos.DeveloperRegistrationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;

import com.sprintiq.pms.tenancy.TenantContext;
import java.util.UUID;
import com.sprintiq.pms.modules.ai.web.dto.OptimizerDtos;
import com.sprintiq.pms.modules.ai.domain.OptimizerSprintRepository;

@Service
public class OptimizerDeveloperService {

    @Autowired
    private OptimizerDeveloperRepository developerRepository;

    @Autowired
    private OptimizerDeveloperProfileRepository developerProfileRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;


    public DeveloperProfile addDeveloper(DeveloperRegistrationRequest request) {
        UUID orgId = request.getOrganizationId();
        if (orgId == null) {
            orgId = TenantContext.organizationId().orElse(null);
        }

        if (orgId == null && request.getEmail() != null && !request.getEmail().isBlank()) {
            try {
                List<UUID> orgs = jdbcTemplate.query(
                        "SELECT m.organization_id FROM users u JOIN org_members m ON m.user_id = u.id WHERE lower(u.email) = lower(?) LIMIT 1",
                        (rs, rowNum) -> (UUID) rs.getObject(1),
                        request.getEmail().trim()
                );
                if (!orgs.isEmpty()) {
                    orgId = orgs.get(0);
                }
            } catch (Exception ignored) {
            }
        }

        if (orgId == null) {
            try {
                List<UUID> defaultOrgs = jdbcTemplate.query(
                        "SELECT id FROM organizations ORDER BY created_at ASC LIMIT 1",
                        (rs, rowNum) -> (UUID) rs.getObject(1)
                );
                if (!defaultOrgs.isEmpty()) {
                    orgId = defaultOrgs.get(0);
                }
            } catch (Exception ignored) {
            }
        }


        Developer developer = new Developer();
        developer.setOrganizationId(orgId);
        developer.setDev_id(request.getDevId());
        developer.setExperience_level(request.getExperienceLevel());
        developer.setSkill_frontend(request.getSkillFrontend());
        developer.setSkill_backend(request.getSkillBackend());
        developer.setSkill_db(request.getSkillDb());
        developer.setCurrent_workload(request.getCurrentWorkload());
        developer.setAvailability(request.getAvailability());
        developer.setCurrent_tasks(request.getCurrentTasks());
        developer.setConsistency(request.getConsistency());
        developer.setLearning_rate(request.getLearningRate());

        DeveloperProfile profile = new DeveloperProfile();
        profile.setOrganizationId(orgId);
        profile.setFirstName(request.getFirstName());
        profile.setLastName(request.getLastName());
        profile.setAge(request.getAge());
        profile.setEmail(request.getEmail());
        profile.setPassword(request.getPassword()); // In a real system, you'd hash this
        profile.setPhoneNumber(request.getPhoneNumber());
        profile.setSpecialization(request.getSpecialization());
        profile.setBio(request.getBio());

        profile.setDeveloperMetrics(developer); // Link them
        developer.setProfile(profile);

        return developerProfileRepository.save(profile);
    }

    public Developer updateDeveloper(Long id, com.sprintiq.pms.modules.ai.web.dto.OptimizerDtos.DeveloperUpdateRequest request) {
        Developer dev = developerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Developer not found with id: " + id));

        if (request.getExperienceLevel() != null) {
            dev.setExperience_level(Math.max(1, Math.min(5, request.getExperienceLevel())));
        }
        if (request.getSkillFrontend() != null) {
            dev.setSkill_frontend(Math.max(1, Math.min(10, request.getSkillFrontend())));
        }
        if (request.getSkillBackend() != null) {
            dev.setSkill_backend(Math.max(1, Math.min(10, request.getSkillBackend())));
        }
        if (request.getSkillDb() != null) {
            dev.setSkill_db(Math.max(1, Math.min(10, request.getSkillDb())));
        }

        DeveloperProfile profile = dev.getProfile();
        if (profile == null) {
            profile = new DeveloperProfile();
            profile.setDeveloperMetrics(dev);
            dev.setProfile(profile);
        }

        if (request.getFirstName() != null) profile.setFirstName(request.getFirstName().trim());
        if (request.getLastName() != null) profile.setLastName(request.getLastName().trim());
        if (request.getEmail() != null && !request.getEmail().isBlank()) profile.setEmail(request.getEmail().trim());
        if (request.getSpecialization() != null) profile.setSpecialization(request.getSpecialization().trim());
        if (request.getBio() != null) profile.setBio(request.getBio().trim());
        if (request.getPhoneNumber() != null) profile.setPhoneNumber(request.getPhoneNumber().trim());
        if (request.getAge() != null) profile.setAge(request.getAge());

        developerProfileRepository.save(profile);
        syncDeveloperWorkload(dev);
        return developerRepository.save(dev);
    }

    @Autowired
    private com.sprintiq.pms.modules.ai.domain.OptimizerTaskRepository taskRepository;

    @Autowired
    private OptimizerSprintRepository sprintRepository;

    public List<DeveloperProfile> getAllDeveloperProfiles() {
        return developerProfileRepository.findAll();
    }

    public List<Developer> getAllDevelopers() {
        List<Developer> devs = developerRepository.findAll();
        for (Developer dev : devs) {
            syncDeveloperWorkload(dev);
        }
        return devs;
    }

    public Developer getDeveloperById(Long id) {
        Developer dev = developerRepository.findById(id).orElseThrow(() -> new RuntimeException("Developer not found"));
        syncDeveloperWorkload(dev);
        return dev;
    }

    private void syncDeveloperWorkload(Developer dev) {
        if (dev == null) return;
        try {
            // First synchronize optimizer_tasks status with core stories status
            if (jdbcTemplate != null) {
                try {
                    jdbcTemplate.update(
                            "UPDATE optimizer_tasks ot " +
                            "SET status = 'COMPLETED', completed_at = NOW() " +
                            "FROM stories st " +
                            "WHERE (ot.task_type LIKE CONCAT('%', st.item_key, '%') OR ot.task_type = st.title) " +
                            "  AND st.status IN ('DONE', 'ACCEPTED', 'CANCELLED') " +
                            "  AND ot.status != 'COMPLETED'"
                    );
                } catch (Exception ignored) {}
            }

            int activePoints = 0;
            int activeTasks = 0;
            boolean hasData = false;

            // 1. Check stories assigned to this developer's core User account
            String email = dev.getProfile() != null ? dev.getProfile().getEmail() : null;
            String firstName = dev.getProfile() != null ? dev.getProfile().getFirstName() : "";
            String lastName = dev.getProfile() != null ? dev.getProfile().getLastName() : "";
            String fullName = (firstName + " " + lastName).trim();

            List<UUID> userIds = new java.util.ArrayList<>();
            if (jdbcTemplate != null) {
                if (email != null && !email.isBlank()) {
                    userIds.addAll(jdbcTemplate.query(
                            "SELECT id FROM users WHERE lower(email) = lower(?)",
                            (rs, rowNum) -> (UUID) rs.getObject(1),
                            email.trim()
                    ));
                }
                if (userIds.isEmpty() && !fullName.isBlank()) {
                    userIds.addAll(jdbcTemplate.query(
                            "SELECT id FROM users WHERE lower(full_name) = lower(?)",
                            (rs, rowNum) -> (UUID) rs.getObject(1),
                            fullName
                    ));
                }
                if (userIds.isEmpty() && dev.getDev_id() != null) {
                    userIds.addAll(jdbcTemplate.query(
                            "SELECT id FROM users WHERE lower(email_normalized) = lower(?)",
                            (rs, rowNum) -> (UUID) rs.getObject(1),
                            dev.getDev_id().trim()
                    ));
                }

                if (!userIds.isEmpty()) {
                    hasData = true;
                    for (UUID userId : userIds) {
                        List<Map<String, Object>> storyStats = jdbcTemplate.queryForList(
                                "SELECT COALESCE(SUM(COALESCE(s.story_points, 3)), 0) AS total_pts, COUNT(s.id) AS total_cnt " +
                                "FROM stories s " +
                                "LEFT JOIN sprints sp ON s.sprint_id = sp.id " +
                                "WHERE s.assignee_id = ? AND s.status NOT IN ('DONE', 'ACCEPTED', 'CANCELLED') " +
                                "AND (s.sprint_id IS NULL OR sp.status = 'ACTIVE') AND s.deleted_at IS NULL",
                                userId
                        );
                        if (!storyStats.isEmpty()) {
                            Number pts = (Number) storyStats.get(0).get("total_pts");
                            Number cnt = (Number) storyStats.get(0).get("total_cnt");
                            activePoints += pts != null ? pts.intValue() : 0;
                            activeTasks += cnt != null ? cnt.intValue() : 0;
                        }
                    }
                }
            }

            // 2. If not linked to a core User, fallback to optimizer tasks table
            if (userIds.isEmpty() && taskRepository != null) {
                List<com.sprintiq.pms.modules.ai.domain.Task> devTasks = taskRepository.findByDeveloperId(dev.getId());
                if (!devTasks.isEmpty()) {
                    hasData = true;
                    for (com.sprintiq.pms.modules.ai.domain.Task t : devTasks) {
                        if (t.getStatus() != com.sprintiq.pms.modules.ai.domain.TaskStatus.COMPLETED) {
                            activePoints += t.getStory_points();
                            activeTasks++;
                        }
                    }
                }
            }

            if (hasData) {
                dev.setCurrent_workload(activePoints);
                dev.setCurrent_tasks(activeTasks);
                double capacity = dev.getEffectiveCapacity();
                double availPct = Math.max(0.0, Math.min(100.0, ((double) Math.max(0.0, capacity - activePoints) / capacity) * 100.0));
                dev.setAvailability(availPct);
                developerRepository.save(dev);
            }
        } catch (Exception e) {
            System.err.println("Note: syncDeveloperWorkload error: " + e.getMessage());
        }
    }

    public DeveloperProfile getDeveloperProfileById(Long id) {
        return developerProfileRepository.findById(id).orElseThrow(() -> new RuntimeException("Profile not found"));
    }

    public DeveloperProfile getProfileByDeveloperId(Long developerId) {
        return developerProfileRepository.findByDeveloperMetricsId(developerId)
                .orElseThrow(() -> new RuntimeException("Profile for developer " + developerId + " not found"));
    }

    // =====================================================
    // UPDATE BEHAVIOR METRICS (FROM ML)
    // =====================================================
    public void updateBehaviorMetrics(List<Map<String, Object>> devs) {
        for (Map<String, Object> dev : devs) {
            Object devIdObj = dev.get("dev_id");
            if (devIdObj == null) continue;

            String devId = devIdObj.toString();
            Optional<Developer> optionalDev = developerRepository.findByDev_id(devId);

            if (optionalDev.isEmpty()) continue;

            Developer developer = optionalDev.get();

            // SAFE parsing (prevents crashes)
            double consistency = parseDoubleSafe(dev.get("consistency"));
            double learningRate = parseDoubleSafe(dev.get("learning_rate"));

            developer.setConsistency(consistency);
            developer.setLearning_rate(learningRate);
            developerRepository.save(developer);

            // Synchronize with core developer_profiles table
            syncWithCoreProfile(developer, dev);
        }
    }

    private void syncWithCoreProfile(Developer developer, Map<String, Object> devData) {
        if (jdbcTemplate == null) {
            return;
        }
        try {
            String email = developer.getProfile() != null ? developer.getProfile().getEmail() : null;
            if (email == null && developer.getDev_id() != null && developer.getDev_id().contains("@")) {
                email = developer.getDev_id();
            }
            if (email == null) return;

            List<UUID> userIds = jdbcTemplate.query(
                    "SELECT id FROM users WHERE lower(email) = lower(?) LIMIT 1",
                    (rs, rowNum) -> (UUID) rs.getObject(1),
                    email.trim()
            );
            if (userIds.isEmpty()) return;
            UUID userId = userIds.get(0);

            Double consistency = developer.getConsistency();
            Double learningRate = developer.getLearning_rate();
            Double avgVelocity = devData.get("avg_velocity") != null ? parseDoubleSafe(devData.get("avg_velocity")) : null;
            Double defectRate = devData.get("defect_rate") != null ? parseDoubleSafe(devData.get("defect_rate")) : null;
            Double reopenRate = devData.get("reopen_rate") != null ? parseDoubleSafe(devData.get("reopen_rate")) : null;

            jdbcTemplate.update(
                    "UPDATE developer_profiles SET " +
                    "consistency_score = COALESCE(?, consistency_score), " +
                    "learning_rate = COALESCE(?, learning_rate), " +
                    "avg_velocity = COALESCE(?, avg_velocity), " +
                    "defect_rate = COALESCE(?, defect_rate), " +
                    "reopen_rate = COALESCE(?, reopen_rate), " +
                    "last_computed_at = NOW() " +
                    "WHERE user_id = ?",
                    consistency, learningRate, avgVelocity, defectRate, reopenRate, userId
            );
        } catch (Exception e) {
            System.err.println("Note: Core profile sync from ML: " + e.getMessage());
        }
    }

    // =====================================================
    // DEVELOPER PERFORMANCE & MULTI-SPRINT ANALYTICS
    // =====================================================
    public OptimizerDtos.DeveloperAnalyticsDto getDeveloperAnalytics(Long developerId) {
        Developer dev = developerRepository.findById(developerId)
                .orElseThrow(() -> new RuntimeException("Developer not found with id: " + developerId));
        syncDeveloperWorkload(dev);

        DeveloperProfile profile = dev.getProfile();
        String firstName = profile != null && profile.getFirstName() != null ? profile.getFirstName() : "";
        String lastName = profile != null && profile.getLastName() != null ? profile.getLastName() : "";
        String fullName = (firstName + " " + lastName).trim();
        if (fullName.isEmpty()) fullName = dev.getDev_id();

        String role = "Developer";
        if (dev.getExperience_level() >= 3) role = "Senior Engineer";
        else if (dev.getExperience_level() >= 2) role = "Mid-level Engineer";
        else role = "Junior Engineer";

        // Fetch all tasks for this developer
        List<com.sprintiq.pms.modules.ai.domain.Task> allTasks = taskRepository.findByDeveloperId(developerId);

        // Group tasks by sprint
        Map<Long, List<com.sprintiq.pms.modules.ai.domain.Task>> tasksBySprint = allTasks.stream()
                .filter(t -> t.getSprint() != null)
                .collect(Collectors.groupingBy(t -> t.getSprint().getId()));

        List<OptimizerDtos.SprintPerformanceDto> sprintHistory = new ArrayList<>();
        int totalCompletedTasks = 0;
        double totalDeliveredSp = 0;
        int totalDefects = 0;
        double totalCycleTimeHours = 0;
        int completedTasksWithTime = 0;

        double feWeightedPoints = 0;
        double beWeightedPoints = 0;
        double dbWeightedPoints = 0;

        // Fetch all sprints to order properly
        List<com.sprintiq.pms.modules.ai.domain.Sprint> allSprints = sprintRepository.findAll();
        allSprints.sort(Comparator.comparing(com.sprintiq.pms.modules.ai.domain.Sprint::getId));

        for (com.sprintiq.pms.modules.ai.domain.Sprint s : allSprints) {
            List<com.sprintiq.pms.modules.ai.domain.Task> sprintTasks = tasksBySprint.getOrDefault(s.getId(), Collections.emptyList());
            if (sprintTasks.isEmpty() && !"ACTIVE".equals(s.getStatus()) && !"PLANNED".equals(s.getStatus())) {
                continue; // Skip historical sprints where dev had no tasks
            }

            double plannedSp = 0;
            double deliveredSp = 0;
            int defects = 0;
            double sprintCycleTime = 0;
            int sprintCompleted = 0;
            List<OptimizerDtos.TaskSummaryDto> taskSummaries = new ArrayList<>();

            for (com.sprintiq.pms.modules.ai.domain.Task t : sprintTasks) {
                plannedSp += t.getStory_points();
                deliveredSp += t.getVelocity_contribution();
                defects += t.getDefects();

                if (t.getStatus() == com.sprintiq.pms.modules.ai.domain.TaskStatus.COMPLETED) {
                    totalCompletedTasks++;
                    totalDeliveredSp += t.getVelocity_contribution();
                    if (t.getCompletion_time() > 0) {
                        sprintCycleTime += t.getCompletion_time();
                        sprintCompleted++;
                        totalCycleTimeHours += t.getCompletion_time();
                        completedTasksWithTime++;
                    }
                }
                totalDefects += t.getDefects();

                feWeightedPoints += t.getStory_points() * (t.getReq_frontend() / 10.0);
                beWeightedPoints += t.getStory_points() * (t.getReq_backend() / 10.0);
                dbWeightedPoints += t.getStory_points() * (t.getReq_db() / 10.0);

                taskSummaries.add(OptimizerDtos.TaskSummaryDto.builder()
                        .id(t.getId())
                        .taskType(t.getTask_type())
                        .storyPoints(t.getStory_points())
                        .complexity(t.getTask_complexity())
                        .status(t.getStatus() != null ? t.getStatus().name() : "PLANNED")
                        .reqFrontend(t.getReq_frontend())
                        .reqBackend(t.getReq_backend())
                        .reqDb(t.getReq_db())
                        .defects(t.getDefects())
                        .build());
            }

            double avgSprintCycleTime = sprintCompleted > 0 ? (sprintCycleTime / sprintCompleted) : 0.0;

            String sprintLabel = s.getSprintId() != null ? s.getSprintId() : ("Sprint " + s.getId());
            if (sprintLabel.contains("(")) {
                sprintLabel = sprintLabel.substring(0, sprintLabel.indexOf("(")).trim();
            }

            sprintHistory.add(OptimizerDtos.SprintPerformanceDto.builder()
                    .sprintId(s.getId())
                    .sprintName(sprintLabel)
                    .status(s.getStatus())
                    .plannedSp(plannedSp)
                    .deliveredSp(deliveredSp)
                    .tasksCount(sprintTasks.size())
                    .defects(defects)
                    .avgCycleTimeHours(Math.round(avgSprintCycleTime * 10.0) / 10.0)
                    .tasks(taskSummaries)
                    .build());
        }

        // Calculate domain distribution
        double totalDomainScore = feWeightedPoints + beWeightedPoints + dbWeightedPoints;
        Map<String, Double> domainMap = new LinkedHashMap<>();
        if (totalDomainScore > 0) {
            domainMap.put("Frontend", Math.round((feWeightedPoints / totalDomainScore) * 1000.0) / 10.0);
            domainMap.put("Backend", Math.round((beWeightedPoints / totalDomainScore) * 1000.0) / 10.0);
            domainMap.put("Database", Math.round((dbWeightedPoints / totalDomainScore) * 1000.0) / 10.0);
        } else {
            domainMap.put("Frontend", 33.3);
            domainMap.put("Backend", 33.3);
            domainMap.put("Database", 33.4);
        }

        double overallAvgCycleTime = completedTasksWithTime > 0 ? (totalCycleTimeHours / completedTasksWithTime) : 16.0;
        double defectRate = totalCompletedTasks > 0 ? (totalDefects / (double) totalCompletedTasks) : 0.0;
        double completionRate = allTasks.isEmpty() ? 100.0 : ((totalCompletedTasks / (double) allTasks.size()) * 100.0);

        // AI Coaching & Talent Evaluation
        List<String> strengths = new ArrayList<>();
        List<String> growthAreas = new ArrayList<>();
        String burnoutRisk = "LOW";
        StringBuilder summary = new StringBuilder();

        // Evaluate Consistency
        if (dev.getConsistency() >= 0.95) {
            strengths.add("Rock-solid sprint delivery consistency (Stability index > 95%). Highly reliable on critical path.");
        } else if (dev.getConsistency() >= 0.85) {
            strengths.add("Consistent sprint throughput with reliable velocity commitment accuracy.");
        } else {
            growthAreas.add("Sprint-to-sprint delivery wobbles observed; benefits from breakdown of high-ambiguity tickets.");
        }

        // Evaluate Domain Specialization
        if (dev.getSkill_db() >= 8) {
            strengths.add("Elite Database Architecture mastery (SQL indexing, concurrency locks, schema partitions).");
        }
        if (dev.getSkill_backend() >= 8) {
            strengths.add("Senior-grade Distributed Backend & API engineering proficiency.");
        }
        if (dev.getSkill_frontend() >= 8) {
            strengths.add("Advanced Frontend Engineering, state architecture, and accessible UI component design.");
        }

        // Evaluate Learning Rate & Workload Saturation
        if (dev.getLearning_rate() > 0.05) {
            strengths.add("Accelerated skill acquisition trajectory (+%d%% growth rate). Strong candidate for complex technical stretch goals."
                    .formatted((int)(dev.getLearning_rate() * 100)));
        } else if (dev.getLearning_rate() < -0.03) {
            growthAreas.add("Negative velocity derivative detected across recent high-point sprints, signaling cumulative cognitive fatigue.");
            burnoutRisk = "HIGH";
        }

        if (dev.getCurrent_workload() > dev.getEffectiveCapacity() * 1.15) {
            burnoutRisk = "HIGH";
            growthAreas.add("Current sprint workload (%d SP) exceeds empirical capacity (%.1f SP). Throttling recommended."
                    .formatted(dev.getCurrent_workload(), dev.getEffectiveCapacity()));
        } else if (dev.getCurrent_workload() > dev.getEffectiveCapacity() * 0.90) {
            if (!"HIGH".equals(burnoutRisk)) burnoutRisk = "MODERATE";
        }

        String coachingRec;
        if ("HIGH".equals(burnoutRisk)) {
            coachingRec = "Action Required: Rebalance sprint workload by delegating 1 ticket to junior/mid engineers. Protect from unplanned interruptions to restore velocity stability.";
            summary.append(fullName).append(" is a high-performing contributor experiencing workload saturation. Proactive capacity governance will safeguard against delivery burnout.");
        } else if (dev.getLearning_rate() > 0.05) {
            coachingRec = "Growth Opportunity: Pair with senior architectural leads on cross-tier integration to prepare for senior promotion.";
            summary.append(fullName).append(" exhibits an exceptional upward learning trajectory and consistent velocity execution across sprints.");
        } else {
            coachingRec = "Maintain current balanced workload. Assign tasks matching core technical strengths while providing gradual exposure to secondary domains.";
            summary.append(fullName).append(" demonstrates steady sprint execution with dependable velocity contribution and healthy workload equilibrium.");
        }

        return OptimizerDtos.DeveloperAnalyticsDto.builder()
                .developerId(dev.getId())
                .devId(dev.getDev_id())
                .fullName(fullName)
                .role(role)
                .specialization(profile != null ? profile.getSpecialization() : "Software Engineer")
                .email(profile != null ? profile.getEmail() : "")
                .bio(profile != null ? profile.getBio() : "")
                .experienceLevel(dev.getExperience_level())
                .consistencyScore(Math.round(dev.getConsistency() * 1000.0) / 10.0)
                .learningRate(Math.round(dev.getLearning_rate() * 10000.0) / 100.0)
                .effectiveCapacity(dev.getEffectiveCapacity())
                .currentWorkload(dev.getCurrent_workload())
                .availability(Math.round(dev.getAvailability() * 10.0) / 10.0)
                .currentTasks(dev.getCurrent_tasks())
                .totalTasksAssigned(allTasks.size())
                .totalTasksCompleted(totalCompletedTasks)
                .totalStoryPointsDelivered(Math.round(totalDeliveredSp * 10.0) / 10.0)
                .avgCycleTimeHours(Math.round(overallAvgCycleTime * 10.0) / 10.0)
                .defectRate(Math.round(defectRate * 1000.0) / 1000.0)
                .completionRate(Math.round(completionRate * 10.0) / 10.0)
                .sprintHistory(sprintHistory)
                .domainDistribution(domainMap)
                .aiExecutiveSummary(summary.toString())
                .strengths(strengths)
                .growthAreas(growthAreas)
                .coachingRecommendation(coachingRec)
                .burnoutRiskLevel(burnoutRisk)
                .build();
    }

    // =====================================================
    // SAFE DOUBLE PARSER
    // =====================================================
    private double parseDoubleSafe(Object value) {
        try {
            if (value == null) return 0.0;
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }
}
