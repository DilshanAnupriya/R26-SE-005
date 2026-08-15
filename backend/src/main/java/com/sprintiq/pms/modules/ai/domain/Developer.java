package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import com.sprintiq.pms.common.domain.TenantScoped;
import com.sprintiq.pms.tenancy.TenantEntityListener;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.hibernate.annotations.Filter;

import java.util.UUID;

@Entity(name = "OptimizerDeveloper")
@Table(name = "optimizer_developers")
@EntityListeners(TenantEntityListener.class)
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "organization_id = :organizationId")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Developer implements TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private UUID organizationId;

    private String dev_id;
    private int experience_level;
    private int skill_frontend;
    private int skill_backend;
    private int skill_db;
    private int current_workload;
    private double availability;
    private int current_tasks;
    private double consistency;
    private double learning_rate;

    @OneToOne(mappedBy = "developerMetrics")
    @JsonIgnoreProperties("developerMetrics")
    private DeveloperProfile profile;
}

