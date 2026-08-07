package com.sprintiq.pms.modules.analytics.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {

    Page<ExportJob> findByRequestedByOrderByCreatedAtDesc(UUID requestedBy, Pageable pageable);

    /**
     * Completed exports past their retention window.
     *
     * <p>Run by the purge job, which deletes the blob and marks the row {@code EXPIRED}
     * rather than removing it — "your export expired, run it again" is a better answer
     * than a 404 on a link the user still has open in a tab.</p>
     */
    @Query("""
            SELECT j FROM ExportJob j
            WHERE j.status = com.sprintiq.pms.modules.analytics.domain.ExportStatus.COMPLETED
              AND j.expiresAt < :now
            """)
    List<ExportJob> findExpired(@Param("now") Instant now, Pageable pageable);
}
