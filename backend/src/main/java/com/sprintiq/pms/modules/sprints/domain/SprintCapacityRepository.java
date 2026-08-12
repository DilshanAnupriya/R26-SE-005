package com.sprintiq.pms.modules.sprints.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SprintCapacityRepository extends JpaRepository<SprintCapacity, UUID> {

    List<SprintCapacity> findBySprintId(UUID sprintId);

    Optional<SprintCapacity> findBySprintIdAndUserId(UUID sprintId, UUID userId);

    void deleteBySprintId(UUID sprintId);
}
