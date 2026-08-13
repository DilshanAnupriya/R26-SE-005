package com.sprintiq.pms.modules.ai.domain;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity(name = "OptimizerTask")
@Table(name = "optimizer_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "sprint_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Sprint sprint;

    @ManyToOne
    @JoinColumn(name = "developer_id")
    private Developer developer;

    private String task_type;
    private int task_complexity;
    private int story_points;

    private int req_frontend;
    private int req_backend;
    private int req_db;

    private double ambiguity;
    private double dependency_risk;

    private double completion_time;
    private int defects;
    private double velocity_contribution;
    private boolean blocked;
    private boolean reopened;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    private LocalDateTime created_at;
    private LocalDateTime started_at;
    private LocalDateTime completed_at;
}
