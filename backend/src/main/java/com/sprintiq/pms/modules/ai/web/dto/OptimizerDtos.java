package com.sprintiq.pms.modules.ai.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.UUID;

public class OptimizerDtos {

    @Data
    public static class DeveloperRegistrationRequest {
        private UUID organizationId;

        // Personal Details
        private String firstName;
        private String lastName;
        private int age;
        private String email;
        private String password;
        private String phoneNumber;
        private String specialization;
        private String bio;

        // ML / Optimization Metrics
        private String devId;
        private int experienceLevel;
        private int skillFrontend;
        private int skillBackend;
        private int skillDb;
        private int currentWorkload;
        private double availability;
        private int currentTasks;
        private double consistency;
        private double learningRate;
    }

    @Data
    public static class DeveloperUpdateRequest {
        private String firstName;
        private String lastName;
        private Integer age;
        private String email;
        private String phoneNumber;
        private String specialization;
        private String bio;

        private Integer experienceLevel;
        private Integer skillFrontend;
        private Integer skillBackend;
        private Integer skillDb;
    }

    @Data
    public static class TaskRequest {
        private UUID organizationId;

        @JsonProperty("task_id")
        private Long taskId;

        @JsonProperty("sprint_id")
        private String sprintId;

        private String task_type;
        private int task_complexity;
        private int story_points;
        private int req_frontend;
        private int req_backend;
        private int req_db;
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DeveloperAnalyticsDto {
        private Long developerId;
        private String devId;
        private String fullName;
        private String role;
        private String specialization;
        private String email;
        private String bio;
        private int experienceLevel;
        private double consistencyScore;
        private double learningRate;
        private double effectiveCapacity;
        private int currentWorkload;
        private double availability;
        private int currentTasks;

        // Cumulative Historical Metrics
        private int totalTasksAssigned;
        private int totalTasksCompleted;
        private double totalStoryPointsDelivered;
        private double avgCycleTimeHours;
        private double defectRate;
        private double completionRate;

        // Sprint-by-Sprint History (for Graphs & Velocity Progression)
        private java.util.List<SprintPerformanceDto> sprintHistory;

        // Domain Handling Breakdown (Percentage of points across domains)
        private java.util.Map<String, Double> domainDistribution;

        // AI Coaching & Talent Evaluation
        private String aiExecutiveSummary;
        private java.util.List<String> strengths;
        private java.util.List<String> growthAreas;
        private String coachingRecommendation;
        private String burnoutRiskLevel;
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SprintPerformanceDto {
        private Long sprintId;
        private String sprintName;
        private String status;
        private double plannedSp;
        private double deliveredSp;
        private int tasksCount;
        private int defects;
        private double avgCycleTimeHours;
        private java.util.List<TaskSummaryDto> tasks;
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskSummaryDto {
        private Long id;
        private String taskType;
        private int storyPoints;
        private int complexity;
        private String status;
        private int reqFrontend;
        private int reqBackend;
        private int reqDb;
        private int defects;
    }
}

