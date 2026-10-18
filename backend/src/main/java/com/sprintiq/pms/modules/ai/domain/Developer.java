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
    @Column(name = "base_velocity")
    private double base_velocity;

    @OneToOne(mappedBy = "developerMetrics")
    @JsonIgnoreProperties("developerMetrics")
    private DeveloperProfile profile;

    /**
     * Calculates empirical capacity derived from historical delivery velocity,
     * scaled by personal availability, falling back to a cold-start prior
     * (experience_level * 10) only when no empirical history is present.
     */
    public double getEffectiveCapacity() {
        double velocity = this.base_velocity > 0 ? this.base_velocity : (this.experience_level > 0 ? this.experience_level : 2) * 10.0;
        double availRatio = this.availability > 0 ? this.availability / 100.0 : 1.0;
        return Math.max(5.0, Math.round(velocity * availRatio * 10.0) / 10.0);
    }
}

