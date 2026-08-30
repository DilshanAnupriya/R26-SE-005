package com.sprintiq.pms.modules.sprints.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SprintCapacityRepository extends JpaRepository<SprintCapacity, UUID> {

    List<SprintCapacity> findBySprintId(UUID sprintId);

    Optional<SprintCapacity> findBySprintIdAndUserId(UUID sprintId, UUID userId);

    /**
     * Whether anyone has entered a capacity plan for this sprint.
     *
     * <p>The difference between "the team had no absences" and "nobody filled the form
     * in" — identical in {@code sprints.absence_days}, which is zero either way, and
     * not at all the same thing to tell a model.</p>
     */
    boolean existsBySprintId(UUID sprintId);

    void deleteBySprintId(UUID sprintId);
}
