package com.sprintiq.pms.modules.roadmap.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InitiativeRepository extends JpaRepository<Initiative, UUID> {

    Optional<Initiative> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * The whole roadmap, ordered the way it is drawn.
     *
     * <p>Unpaged deliberately. A roadmap is a picture of everything in flight — showing
     * page one of it would be worse than useless, because the reader would draw
     * conclusions from an arbitrary subset.</p>
     *
     * <p>The date window is applied by the caller rather than here. An optional date
     * parameter written as {@code :from IS NULL OR …} gives Postgres a bare parameter
     * with no type context, and it refuses the whole statement with "could not
     * determine data type". Since this query is unpaged anyway, filtering a roadmap's
     * worth of rows in Java costs nothing and cannot be got wrong.</p>
     */
    @Query("""
            SELECT i FROM Initiative i
            WHERE i.deletedAt IS NULL
              AND (:projectId IS NULL OR i.projectId = :projectId)
              AND (:status IS NULL OR i.status = :status)
            ORDER BY i.position ASC, i.targetDate ASC NULLS LAST, i.createdAt ASC
            """)
    List<Initiative> findForRoadmap(@Param("projectId") UUID projectId,
                                    @Param("status") InitiativeStatus status);

    /**
     * Open initiatives across every organization, for the nightly re-pacing sweep.
     *
     * <p>Runs with no tenant context and therefore outside the Hibernate filter —
     * deliberately, because the calendar does not stop for tenants nobody logged into.
     * Nothing serving a request may call this.</p>
     */
    List<Initiative> findByStatusInAndDeletedAtIsNull(Collection<InitiativeStatus> statuses);

    /** Highest position in a lane, so a new initiative appends rather than collides. */
    @Query("""
            SELECT MAX(i.position) FROM Initiative i
            WHERE i.deletedAt IS NULL
              AND (:projectId IS NULL AND i.projectId IS NULL OR i.projectId = :projectId)
            """)
    Optional<Short> findMaxPosition(@Param("projectId") UUID projectId);
}
