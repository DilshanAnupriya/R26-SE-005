package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SprintRiskAdjustmentRepository extends JpaRepository<SprintRiskAdjustment, UUID> {

    List<SprintRiskAdjustment> findBySprintIdOrderByCreatedAtDesc(UUID sprintId);

    List<SprintRiskAdjustment> findBySprintIdInOrderByCreatedAtDesc(List<UUID> sprintIds);

    /** The revisable-signal lookup. Unique per sprint by database constraint. */
    Optional<SprintRiskAdjustment> findBySprintIdAndSourceKey(UUID sprintId, String sourceKey);

    boolean existsBySprintIdAndSourceKey(UUID sprintId, String sourceKey);

    void deleteBySprintIdAndSourceKey(UUID sprintId, String sourceKey);
}
