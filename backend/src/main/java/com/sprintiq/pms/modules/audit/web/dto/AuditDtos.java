package com.sprintiq.pms.modules.audit.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Response records for the audit trail. */
public final class AuditDtos {

    private AuditDtos() {}

    @Schema(name = "AuditEntryResponse", description = "One entry in the governance trail")
    public record AuditEntryResponse(
            UUID id,
            UUID actorUserId,
            String actorEmail,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> before,
            Map<String, Object> after,
            String outcome,
            String ipAddress,
            String correlationId,
            Instant createdAt) {}
}
