package com.sprintiq.pms.modules.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Entity(name = "OptimizerSprint")
@Table(name = "optimizer_sprints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
