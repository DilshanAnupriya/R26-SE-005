package com.sprintiq.pms.modules.tasks.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {

    interface UserEffortSummary {
        UUID getUserId();
        Long getMinutes();
        Long getOvertimeMinutes();
    }

    List<TimeEntry> findByEntityTypeAndEntityIdOrderByLoggedOnDesc(EntityType entityType, UUID entityId);

    /**
     * The caller's running timer, if any. One at a time per person is a deliberate
     * product rule — a human cannot be doing two things at once, and allowing it makes
     * every timesheet double-count.
     */
    @Query("""
            SELECT t FROM TimeEntry t
            WHERE t.userId = :userId AND t.startedAt IS NOT NULL AND t.stoppedAt IS NULL
            """)
    Optional<TimeEntry> findRunningTimer(@Param("userId") UUID userId);

    @Query("""
            SELECT t FROM TimeEntry t
            WHERE t.projectId = :projectId
              AND (:userId IS NULL OR t.userId = :userId)
              AND (:sprintId IS NULL OR t.sprintId = :sprintId)
              AND (CAST(:from AS date) IS NULL OR t.loggedOn >= :from)
              AND (CAST(:to AS date) IS NULL OR t.loggedOn <= :to)
            """)
    Page<TimeEntry> search(@Param("projectId") UUID projectId,
                           @Param("userId") UUID userId,
                           @Param("sprintId") UUID sprintId,
                           @Param("from") LocalDate from,
                           @Param("to") LocalDate to,
                           Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(t.minutes), 0) FROM TimeEntry t
            WHERE t.entityType = :entityType AND t.entityId = :entityId
            """)
    long sumMinutesFor(@Param("entityType") EntityType entityType, @Param("entityId") UUID entityId);

    @Query("""
            SELECT COALESCE(SUM(t.minutes), 0) FROM TimeEntry t
            WHERE t.sprintId = :sprintId
            """)
    long sumMinutesInSprint(@Param("sprintId") UUID sprintId);

    /**
     * Minutes one person logged, optionally narrowed to a project or a sprint.
     *
     * <p>Summed in SQL rather than by paging the timesheet: the productivity report asks
     * this once per team member, and a team of twelve would otherwise page twelve
     * timesheets to add up twelve numbers.</p>
     */
    @Query("""
            SELECT COALESCE(SUM(t.minutes), 0) FROM TimeEntry t
            WHERE t.userId = :userId
              AND (:projectId IS NULL OR t.projectId = :projectId)
              AND (:sprintId IS NULL OR t.sprintId = :sprintId)
            """)
    long sumMinutesLoggedBy(@Param("userId") UUID userId,
                            @Param("projectId") UUID projectId,
                            @Param("sprintId") UUID sprintId);

    /** Overtime hours in a sprint — priced separately by the M4 cost model. */
    @Query("""
            SELECT COALESCE(SUM(t.minutes), 0) FROM TimeEntry t
            WHERE t.sprintId = :sprintId AND t.overtime = TRUE
            """)
    long sumOvertimeMinutesInSprint(@Param("sprintId") UUID sprintId);

    @Query("""
            SELECT t.userId AS userId,
                   SUM(t.minutes) AS minutes,
                   SUM(CASE WHEN t.overtime = TRUE THEN t.minutes ELSE 0 END) AS overtimeMinutes
            FROM TimeEntry t
            WHERE t.sprintId = :sprintId
            GROUP BY t.userId
            """)
    List<UserEffortSummary> effortByUserInSprint(@Param("sprintId") UUID sprintId);
}
