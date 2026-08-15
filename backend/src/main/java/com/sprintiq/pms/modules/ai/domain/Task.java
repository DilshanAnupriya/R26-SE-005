package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import com.sprintiq.pms.common.domain.TenantScoped;
import com.sprintiq.pms.tenancy.TenantEntityListener;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.Filter;

@Entity(name = "OptimizerTask")
@Table(name = "optimizer_tasks")
@EntityListeners(TenantEntityListener.class)
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "organization_id = :organizationId")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Task implements TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private UUID organizationId;

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

