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
}

