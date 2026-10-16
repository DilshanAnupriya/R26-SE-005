package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import com.sprintiq.pms.common.domain.TenantScoped;
import com.sprintiq.pms.tenancy.TenantEntityListener;
import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.Filter;

import java.util.UUID;

@Entity(name = "OptimizerAllocation")
@Table(name = "optimizer_allocations")
@EntityListeners(TenantEntityListener.class)
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Allocation implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @ManyToOne
    @JoinColumn(name = "sprint_id")
    @JsonIgnore
    private Sprint sprint;

    @ManyToOne
    @JoinColumn(name = "task_id")
    private Task task;

    @ManyToOne
    @JoinColumn(name = "developer_id")
    private Developer developer;

    private Double mlScore;

    private Double skillMatchScore;

    private Double workloadBalance;

    private Double finalScore;

    private Integer rankPosition;

    private String status;

    @Column(columnDefinition = "TEXT")
    private String explanation;
}

