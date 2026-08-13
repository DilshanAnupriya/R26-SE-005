package com.sprintiq.pms.modules.ai.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity(name = "OptimizerDeveloperProfile")
@Table(name = "optimizer_developer_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeveloperProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
