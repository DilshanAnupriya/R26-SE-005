package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import com.sprintiq.pms.common.domain.TenantScoped;
import com.sprintiq.pms.tenancy.TenantEntityListener;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.util.UUID;

@Entity(name = "OptimizerDeveloperProfile")
@Table(name = "optimizer_developer_profiles")
@EntityListeners(TenantEntityListener.class)
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "organization_id = :organizationId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeveloperProfile implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private UUID organizationId;

    private String firstName;
    private String lastName;
    private int age;
    private String email;
    private String password;
    private String phoneNumber;
    private String specialization;
    @Column(length = 1000)
    private String bio;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "developer_metrics_id", referencedColumnName = "id")
    private Developer developerMetrics;
}

