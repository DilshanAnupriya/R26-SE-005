package com.sprintiq.pms.modules.sprints.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SprintRepository extends JpaRepository<Sprint, UUID> {

    Optional<Sprint> findByIdAndDeletedAtIsNull(UUID id);

    List<Sprint> findByProjectIdAndDeletedAtIsNullOrderBySequenceNoDesc(UUID projectId);

    /** At most one sprint per project may be ACTIVE — enforced by a partial unique index too. */
    Optional<Sprint> findByProjectIdAndStatusAndDeletedAtIsNull(UUID projectId, SprintStatus status);

    Optional<Sprint> findFirstByProjectIdAndStatusAndDeletedAtIsNullOrderBySequenceNoAsc(
            UUID projectId, SprintStatus status);

    @Query("""
            SELECT s FROM Sprint s
            WHERE s.deletedAt IS NULL
              AND (:projectId IS NULL OR s.projectId = :projectId)
              AND (:status IS NULL OR s.status = :status)
              AND (:search IS NULL OR LOWER(s.name) LIKE :search)
            """)
    Page<Sprint> search(@Param("projectId") UUID projectId,
                        @Param("status") SprintStatus status,
                        @Param("search") String search,
                        Pageable pageable);

    @Query("""
            SELECT COALESCE(MAX(s.sequenceNo), 0) FROM Sprint s
            WHERE s.projectId = :projectId
            """)
    int findMaxSequenceNo(@Param("projectId") UUID projectId);

    /**
     * Recent completed sprints, newest first — the history the velocity chart plots and
     * the M1 velocity model uses as its rolling window.
     */
    @Query("""
            SELECT s FROM Sprint s
            WHERE s.projectId = :projectId AND s.deletedAt IS NULL AND s.status = 'COMPLETED'
            ORDER BY s.sequenceNo DESC
            """)
    List<Sprint> findCompletedHistory(@Param("projectId") UUID projectId, Pageable pageable);
}
