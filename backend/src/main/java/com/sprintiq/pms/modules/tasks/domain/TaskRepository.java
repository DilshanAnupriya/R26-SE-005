package com.sprintiq.pms.modules.tasks.domain;

import com.sprintiq.pms.common.domain.Priority;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Task> findByIdAndDeletedAtIsNotNull(UUID id);

    List<Task> findByStoryIdAndDeletedAtIsNullOrderByRankAsc(UUID storyId);

    List<Task> findByParentTaskIdAndDeletedAtIsNullOrderByRankAsc(UUID parentTaskId);

    List<Task> findBySprintIdAndDeletedAtIsNull(UUID sprintId);

    long countByProjectIdAndDeletedAtIsNull(UUID projectId);

    long countByWorkflowStateIdAndDeletedAtIsNull(UUID workflowStateId);

    @Query("""
            SELECT t FROM Task t
            WHERE t.deletedAt IS NULL
              AND (:projectId IS NULL OR t.projectId = :projectId)
              AND (:storyId IS NULL OR t.storyId = :storyId)
              AND (:sprintId IS NULL OR t.sprintId = :sprintId)
              AND (:assigneeId IS NULL OR t.assigneeId = :assigneeId)
              AND (:status IS NULL OR t.status = :status)
              AND (:priority IS NULL OR t.priority = :priority)
              AND (:blocked IS NULL OR t.blocked = :blocked)
              AND (CAST(:dueBefore AS date) IS NULL OR t.dueDate <= :dueBefore)
              AND (:topLevelOnly = FALSE OR t.parentTaskId IS NULL)
              AND (:search IS NULL OR LOWER(t.title) LIKE :search OR LOWER(t.itemKey) LIKE :search)
            """)
    Page<Task> search(@Param("projectId") UUID projectId,
                      @Param("storyId") UUID storyId,
                      @Param("sprintId") UUID sprintId,
                      @Param("assigneeId") UUID assigneeId,
                      @Param("status") TaskStatus status,
                      @Param("priority") Priority priority,
                      @Param("blocked") Boolean blocked,
                      @Param("dueBefore") LocalDate dueBefore,
                      @Param("topLevelOnly") boolean topLevelOnly,
                      @Param("search") String search,
                      Pageable pageable);

    @Query("""
            SELECT MAX(t.rank) FROM Task t
            WHERE t.projectId = :projectId AND t.deletedAt IS NULL
            """)
    Optional<String> findMaxRank(@Param("projectId") UUID projectId);

    // ---------------------------------------------------------------- roll-ups

    @Query("""
            SELECT COUNT(t) FROM Task t
            WHERE t.projectId = :projectId AND t.deletedAt IS NULL
              AND t.status <> 'DONE' AND t.status <> 'CANCELLED'
            """)
    long countOpen(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COUNT(t) FROM Task t
            WHERE t.projectId = :projectId AND t.deletedAt IS NULL AND t.blocked = TRUE
            """)
    long countBlocked(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COALESCE(SUM(t.spentMinutes), 0) FROM Task t
            WHERE t.sprintId = :sprintId AND t.deletedAt IS NULL
            """)
    long sumSpentMinutesInSprint(@Param("sprintId") UUID sprintId);

    /** Open tasks per person — the workload board and the M3 current-load feature. */
    @Query("""
            SELECT t FROM Task t
            WHERE t.assigneeId = :userId AND t.deletedAt IS NULL
              AND t.status <> 'DONE' AND t.status <> 'CANCELLED'
            """)
    List<Task> findOpenForUser(@Param("userId") UUID userId);
}
