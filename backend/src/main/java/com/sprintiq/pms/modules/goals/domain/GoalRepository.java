package com.sprintiq.pms.modules.goals.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {

    Optional<Goal> findByIdAndDeletedAtIsNull(UUID id);

    List<Goal> findByParentGoalIdAndDeletedAtIsNull(UUID parentGoalId);

    @Query("""
            SELECT g FROM Goal g
            WHERE g.deletedAt IS NULL
              AND (:period IS NULL OR g.period = :period)
              AND (:status IS NULL OR g.status = :status)
              AND (:level IS NULL OR g.level = :level)
              AND (:projectId IS NULL OR g.projectId = :projectId)
              AND (:ownerId IS NULL OR g.ownerUserId = :ownerId)
              AND (:search IS NULL OR LOWER(g.title) LIKE :search)
            """)
    Page<Goal> search(@Param("period") String period,
                      @Param("status") GoalStatus status,
                      @Param("level") GoalLevel level,
                      @Param("projectId") UUID projectId,
                      @Param("ownerId") UUID ownerId,
                      @Param("search") String search,
                      Pageable pageable);

    /** Distinct cycles that have goals, newest first — drives the period picker. */
    @Query("""
            SELECT DISTINCT g.period FROM Goal g
            WHERE g.deletedAt IS NULL AND g.period IS NOT NULL
            ORDER BY g.period DESC
            """)
    List<String> findPeriods();
}
