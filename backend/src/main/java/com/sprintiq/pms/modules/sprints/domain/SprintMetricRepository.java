package com.sprintiq.pms.modules.sprints.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SprintMetricRepository extends JpaRepository<SprintMetric, UUID> {

    List<SprintMetric> findBySprintIdOrderBySnapshotDateAsc(UUID sprintId);

    Optional<SprintMetric> findBySprintIdAndSnapshotDate(UUID sprintId, LocalDate snapshotDate);
}
