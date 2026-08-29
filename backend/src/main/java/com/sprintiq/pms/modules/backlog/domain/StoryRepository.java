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

    /** The recycle bin's contents for this type, most recently deleted first. */
    org.springframework.data.domain.Page<Story> findByDeletedAtIsNotNullOrderByDeletedAtDesc(
            org.springframework.data.domain.Pageable pageable);

    List<Story> findByIdInAndDeletedAtIsNull(Collection<UUID> ids);

    List<Story> findByAssigneeIdAndDeletedAtIsNull(UUID assigneeId);

    List<Story> findByAssigneeIdInAndDeletedAtIsNull(Collection<UUID> assigneeIds);

    List<Story> findBySprintIdAndDeletedAtIsNull(UUID sprintId);

    List<Story> findByEpicIdAndDeletedAtIsNullOrderByRankAsc(UUID epicId);

    long countByProjectIdAndDeletedAtIsNull(UUID projectId);

    long countByEpicIdAndDeletedAtIsNull(UUID epicId);

    long countByWorkflowStateIdAndDeletedAtIsNull(UUID workflowStateId);

    long countBySprintIdAndDeletedAtIsNull(UUID sprintId);

    long countByProjectIdAndStoryPointsIsNotNullAndDeletedAtIsNull(UUID projectId);

    long countByProjectIdAndEstimateSourceInAndDeletedAtIsNull(UUID projectId,
                                                              Collection<EstimateSource> sources);

    /**
     * Estimated points across a project, with each story's type.
     *
     * <p>Feeds the anchor the M1 estimator works from. Returned as rows rather than as a
     * SQL median because the estimator needs two medians — the project's and one
     * restricted to comparable stories — and computing them from a single scan beats two
     * round trips for a set this size. Points are never in the millions; a project with
     * ten thousand estimated stories has bigger problems than this query.</p>
     */
    @Query("""
            SELECT s.type AS type, s.storyPoints AS points
            FROM Story s
            WHERE s.projectId = :projectId AND s.deletedAt IS NULL AND s.storyPoints IS NOT NULL
            """)
    List<EstimatedPoint> findEstimatedPoints(@Param("projectId") UUID projectId);

    /** Projection for {@link #findEstimatedPoints}. */
    interface EstimatedPoint {
        StoryType getType();

        BigDecimal getPoints();
    }

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

    /**
     * Total and completed points for many projects in one query.
     *
     * <p>The projects list needs this for every card it draws. Calling the two
     * single-project sums per card would put forty queries behind one page.</p>
     */
    @Query("""
            SELECT s.projectId,
                   COALESCE(SUM(s.storyPoints), 0),
                   COALESCE(SUM(CASE WHEN s.status = 'DONE' THEN s.storyPoints ELSE 0 END), 0)
            FROM Story s
            WHERE s.projectId IN :projectIds AND s.deletedAt IS NULL
            GROUP BY s.projectId
            """)
    List<Object[]> sumPointsByProject(@Param("projectIds") Collection<UUID> projectIds);

    /**
     * Story counts and points per epic, for a batch of epics in one query.
     *
     * <p>Grouped rather than fetched-and-counted because the caller is the roadmap,
     * which asks about every epic on the page at once. The per-epic form of this
     * question is how a roadmap of thirty lanes becomes a hundred round trips.</p>
     *
     * @return rows of (epicId, totalStories, doneStories, totalPoints, donePoints)
     */
    @Query("""
            SELECT s.epicId,
                   COUNT(s),
                   COALESCE(SUM(CASE WHEN s.status = 'DONE' THEN 1 ELSE 0 END), 0),
                   COALESCE(SUM(COALESCE(s.storyPoints, 0)), 0),
                   COALESCE(SUM(CASE WHEN s.status = 'DONE'
                                     THEN COALESCE(s.storyPoints, 0) ELSE 0 END), 0)
            FROM Story s
            WHERE s.epicId IN :epicIds AND s.deletedAt IS NULL
            GROUP BY s.epicId
            """)
    List<Object[]> rollUpByEpic(@Param("epicIds") Collection<UUID> epicIds);

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
