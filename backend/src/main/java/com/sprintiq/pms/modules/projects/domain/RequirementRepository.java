package com.sprintiq.pms.modules.projects.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RequirementRepository extends JpaRepository<Requirement, UUID> {

    Optional<Requirement> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByProjectIdAndReferenceCode(UUID projectId, String referenceCode);

    long countByProjectIdAndDeletedAtIsNull(UUID projectId);

    @Query("""
            SELECT r FROM Requirement r
            WHERE r.projectId = :projectId AND r.deletedAt IS NULL
              AND (:status IS NULL OR r.status = :status)
              AND (:type IS NULL OR r.type = :type)
              AND (:search IS NULL OR LOWER(r.title) LIKE :search OR LOWER(r.referenceCode) LIKE :search)
            """)
    Page<Requirement> search(@Param("projectId") UUID projectId,
                             @Param("status") RequirementStatus status,
                             @Param("type") RequirementType type,
                             @Param("search") String search,
                             Pageable pageable);

    /**
     * Total post-approval churn across a project's requirements — the aggregate the M2
     * requirement-change model consumes as a volatility feature.
     */
    @Query("""
            SELECT COALESCE(SUM(r.changeCount), 0) FROM Requirement r
            WHERE r.projectId = :projectId AND r.deletedAt IS NULL
            """)
    long totalChangeCount(@Param("projectId") UUID projectId);

    /** Highest reference number already issued, so the next code continues the run. */
    @Query("SELECT COUNT(r) FROM Requirement r WHERE r.projectId = :projectId")
    long countAllIncludingDeleted(@Param("projectId") UUID projectId);
}
