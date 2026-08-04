package com.sprintiq.pms.modules.projects.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndDeletedAtIsNull(UUID id);

    /** Recycle-bin lookup: deliberately includes soft-deleted rows so restore can find them. */
    Optional<Project> findByIdAndDeletedAtIsNotNull(UUID id);

    boolean existsByOrganizationIdAndProjectKey(UUID organizationId, String projectKey);

    List<Project> findByWorkspaceIdAndDeletedAtIsNull(UUID workspaceId);

    /**
     * Serialises key allocation. Two concurrent "create story" calls would otherwise
     * both read the same {@code item_sequence} and mint the same key; the row lock
     * makes the read-increment-write atomic.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT p FROM Project p
            WHERE p.deletedAt IS NULL
              AND (:workspaceId IS NULL OR p.workspaceId = :workspaceId)
              AND (:status IS NULL OR p.status = :status)
              AND (:health IS NULL OR p.health = :health)
              AND (:leadId IS NULL OR p.leadUserId = :leadId)
              AND (:search IS NULL OR LOWER(p.name) LIKE :search OR LOWER(p.projectKey) LIKE :search)
            """)
    Page<Project> search(@Param("workspaceId") UUID workspaceId,
                         @Param("status") ProjectStatus status,
                         @Param("health") ProjectHealth health,
                         @Param("leadId") UUID leadId,
                         @Param("search") String search,
                         Pageable pageable);

    Page<Project> findByDeletedAtIsNotNull(Pageable pageable);
}
