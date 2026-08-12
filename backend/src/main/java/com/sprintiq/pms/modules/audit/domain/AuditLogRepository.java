package com.sprintiq.pms.modules.audit.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.organizationId = :organizationId
              AND (:actorId IS NULL OR a.actorUserId = :actorId)
              AND (:action IS NULL OR a.action = :action)
              AND (:entityType IS NULL OR a.entityType = :entityType)
              AND (:entityId IS NULL OR a.entityId = :entityId)
              AND (CAST(:from AS timestamp) IS NULL OR a.createdAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR a.createdAt <= :to)
            """)
    Page<AuditLog> search(@Param("organizationId") UUID organizationId,
                          @Param("actorId") UUID actorId,
                          @Param("action") String action,
                          @Param("entityType") String entityType,
                          @Param("entityId") UUID entityId,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);
}
