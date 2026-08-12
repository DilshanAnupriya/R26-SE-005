package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CostForecastRepository extends JpaRepository<CostForecast, UUID> {

    Optional<CostForecast> findFirstBySprintIdAndStageOrderBySnapshotAtDesc(UUID sprintId, CostStage stage);

    Optional<CostForecast> findFirstBySprintIdOrderBySnapshotAtDesc(UUID sprintId);

    List<CostForecast> findBySprintIdOrderBySnapshotAtAsc(UUID sprintId);

    /** The cost-trend chart: the newest forecast for each sprint in a project. */
    @Query("""
            SELECT c FROM CostForecast c
            WHERE c.projectId = :projectId
              AND c.snapshotAt = (
                  SELECT MAX(c2.snapshotAt) FROM CostForecast c2 WHERE c2.sprintId = c.sprintId)
            ORDER BY c.snapshotAt ASC
            """)
    List<CostForecast> findLatestPerSprint(@Param("projectId") UUID projectId);
}
