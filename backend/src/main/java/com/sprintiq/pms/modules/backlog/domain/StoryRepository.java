package com.sprintiq.pms.modules.backlog.domain;

import com.sprintiq.pms.common.domain.Priority;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoryRepository extends JpaRepository<Story, UUID> {

    Optional<Story> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Story> findByIdAndDeletedAtIsNotNull(UUID id);

    List<Story> findByIdInAndDeletedAtIsNull(Collection<UUID> ids);

    List<Story> findBySprintIdAndDeletedAtIsNull(UUID sprintId);

    List<Story> findByEpicIdAndDeletedAtIsNullOrderByRankAsc(UUID epicId);

    long countByProjectIdAndDeletedAtIsNull(UUID projectId);

    long countByEpicIdAndDeletedAtIsNull(UUID epicId);

    long countByWorkflowStateIdAndDeletedAtIsNull(UUID workflowStateId);

    long countBySprintIdAndDeletedAtIsNull(UUID sprintId);

    /**
     * The backlog: everything in the project, ordered by rank, with the standard filter
     * set. {@code unassignedSprintOnly} implements the API's {@code sprintId=none}
     * filter — the true backlog view of items not yet committed to any sprint.
     */
    @Query("""
            SELECT s FROM Story s
            WHERE s.projectId = :projectId AND s.deletedAt IS NULL
              AND (:epicId IS NULL OR s.epicId = :epicId)
              AND (:status IS NULL OR s.status = :status)
              AND (:type IS NULL OR s.type = :type)
              AND (:priority IS NULL OR s.priority = :priority)
              AND (:assigneeId IS NULL OR s.assigneeId = :assigneeId)
              AND (:sprintId IS NULL OR s.sprintId = :sprintId)
              AND (:unassignedSprintOnly = FALSE OR s.sprintId IS NULL)
              AND (:search IS NULL OR LOWER(s.title) LIKE :search OR LOWER(s.itemKey) LIKE :search)
            """)
    Page<Story> search(@Param("projectId") UUID projectId,
                       @Param("epicId") UUID epicId,
                       @Param("status") StoryStatus status,
                       @Param("type") StoryType type,
                       @Param("priority") Priority priority,
                       @Param("assigneeId") UUID assigneeId,
                       @Param("sprintId") UUID sprintId,
                       @Param("unassignedSprintOnly") boolean unassignedSprintOnly,
                       @Param("search") String search,
                       Pageable pageable);

    @Query("""
            SELECT MAX(s.rank) FROM Story s
            WHERE s.projectId = :projectId AND s.deletedAt IS NULL
            """)
    Optional<String> findMaxRank(@Param("projectId") UUID projectId);

    // ---------------------------------------------------------------- roll-ups

    @Query("""
            SELECT COUNT(s) FROM Story s
            WHERE s.projectId = :projectId AND s.deletedAt IS NULL AND s.status = 'DONE'
            """)
    long countCompleted(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COALESCE(SUM(s.storyPoints), 0) FROM Story s
            WHERE s.projectId = :projectId AND s.deletedAt IS NULL
            """)
    BigDecimal sumPoints(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COALESCE(SUM(s.storyPoints), 0) FROM Story s
            WHERE s.projectId = :projectId AND s.deletedAt IS NULL AND s.status = 'DONE'
            """)
    BigDecimal sumCompletedPoints(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COUNT(s) FROM Story s
            WHERE s.projectId = :projectId AND s.deletedAt IS NULL AND s.blocked = TRUE
            """)
    long countBlocked(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COALESCE(SUM(s.storyPoints), 0) FROM Story s
            WHERE s.sprintId = :sprintId AND s.deletedAt IS NULL
            """)
    BigDecimal sumPointsInSprint(@Param("sprintId") UUID sprintId);

    /** Delivered points for a sprint. Spikes are excluded — they are not velocity. */
    @Query("""
            SELECT COALESCE(SUM(s.storyPoints), 0) FROM Story s
            WHERE s.sprintId = :sprintId AND s.deletedAt IS NULL
              AND s.status = 'DONE' AND s.type <> 'SPIKE'
            """)
    BigDecimal sumCompletedPointsInSprint(@Param("sprintId") UUID sprintId);

    /** Items that did not finish — the candidates for spillover when a sprint closes. */
    @Query("""
            SELECT s FROM Story s
            WHERE s.sprintId = :sprintId AND s.deletedAt IS NULL
              AND s.status <> 'DONE' AND s.status <> 'CANCELLED'
            """)
    List<Story> findIncompleteInSprint(@Param("sprintId") UUID sprintId);
}
