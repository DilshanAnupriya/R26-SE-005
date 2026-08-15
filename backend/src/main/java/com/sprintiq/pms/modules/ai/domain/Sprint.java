package com.sprintiq.pms.modules.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import com.sprintiq.pms.common.domain.TenantScoped;
import com.sprintiq.pms.tenancy.TenantEntityListener;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity(name = "OptimizerSprint")
@Table(name = "optimizer_sprints")
@EntityListeners(TenantEntityListener.class)
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sprint implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private UUID organizationId;

    private String sprintId;

    private LocalDate startDate;

    private LocalDate endDate;

    private String status;

    @OneToMany(mappedBy = "sprint", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<Allocation> allocations;

    @OneToMany(mappedBy = "sprint", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<Task> tasks;
}

