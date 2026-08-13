package com.sprintiq.pms.modules.ai.domain;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity(name = "OptimizerAllocation")
@Table(name = "optimizer_allocations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Allocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(length = 2000)
    private String explanation;
}
