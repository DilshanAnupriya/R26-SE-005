package com.sprintiq.pms.modules.backlog.domain;

import com.sprintiq.pms.common.domain.Priority;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EpicRepository extends JpaRepository<Epic, UUID> {

    Optional<Epic> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Epic> findByIdAndDeletedAtIsNotNull(UUID id);

    List<Epic> findByProjectIdAndDeletedAtIsNullOrderByRankAsc(UUID projectId);

    long countByProjectIdAndDeletedAtIsNull(UUID projectId);

    @Query("""
            SELECT e FROM Epic e
            WHERE e.projectId = :projectId AND e.deletedAt IS NULL
              AND (:status IS NULL OR e.status = :status)
              AND (:priority IS NULL OR e.priority = :priority)
              AND (:milestoneId IS NULL OR e.milestoneId = :milestoneId)
              AND (:search IS NULL OR LOWER(e.title) LIKE :search OR LOWER(e.itemKey) LIKE :search)
            """)
    Page<Epic> search(@Param("projectId") UUID projectId,
                      @Param("status") EpicStatus status,
                      @Param("priority") Priority priority,
                      @Param("milestoneId") UUID milestoneId,
                      @Param("search") String search,
                      Pageable pageable);

    /** Highest rank in the project, so a new epic can be appended without reading the list. */
    @Query("""
            SELECT MAX(e.rank) FROM Epic e
            WHERE e.projectId = :projectId AND e.deletedAt IS NULL
            """)
    Optional<String> findMaxRank(@Param("projectId") UUID projectId);
}
