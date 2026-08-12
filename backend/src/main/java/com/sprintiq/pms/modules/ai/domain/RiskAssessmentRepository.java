package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {

    Optional<RiskAssessment> findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(UUID sprintId, RiskType riskType);

    List<RiskAssessment> findBySprintIdOrderByCreatedAtDesc(UUID sprintId);

    /** The trend line: one risk type's history for a sprint, oldest first. */
    List<RiskAssessment> findBySprintIdAndRiskTypeOrderByCreatedAtAsc(UUID sprintId, RiskType riskType);

    /**
     * Latest assessment per (sprint, type) across a project — the risk summary report.
     * The correlated subquery keeps this to one row per pair rather than returning the
     * whole history and thinning it in Java.
     */
    @Query("""
            SELECT r FROM RiskAssessment r
            WHERE r.projectId = :projectId
              AND r.createdAt = (
                  SELECT MAX(r2.createdAt) FROM RiskAssessment r2
                  WHERE r2.sprintId = r.sprintId AND r2.riskType = r.riskType)
            ORDER BY r.riskScore DESC
            """)
    List<RiskAssessment> findLatestForProject(@Param("projectId") UUID projectId, Pageable pageable);
}
