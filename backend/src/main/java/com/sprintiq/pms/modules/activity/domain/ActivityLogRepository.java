package com.sprintiq.pms.modules.activity.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {

    Page<ActivityLog> findByEntityTypeAndEntityId(String entityType, UUID entityId, Pageable pageable);

    /**
     * The organization is passed explicitly rather than relying on the tenant filter:
     * {@code ActivityLog} is append-only history that does not extend
     * {@code TenantAwareEntity}, so its scoping is enforced here in the query.
     */
    @Query("""
            SELECT a FROM ActivityLog a
            WHERE a.organizationId = :organizationId
              AND (:projectId IS NULL OR a.projectId = :projectId)
              AND (:entityType IS NULL OR a.entityType = :entityType)
              AND (:entityId IS NULL OR a.entityId = :entityId)
              AND (:actorId IS NULL OR a.actorId = :actorId)
              AND (CAST(:from AS timestamp) IS NULL OR a.createdAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR a.createdAt <= :to)
            """)
    Page<ActivityLog> search(@Param("organizationId") UUID organizationId,
                             @Param("projectId") UUID projectId,
                             @Param("entityType") String entityType,
                             @Param("entityId") UUID entityId,
                             @Param("actorId") UUID actorId,
                             @Param("from") Instant from,
                             @Param("to") Instant to,
                             Pageable pageable);

    void deleteByEntityTypeAndEntityId(String entityType, UUID entityId);
}
