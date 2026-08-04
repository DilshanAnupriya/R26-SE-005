package com.sprintiq.pms.modules.audit.web;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.audit.domain.AuditLog;
import com.sprintiq.pms.modules.audit.domain.AuditLogRepository;
import com.sprintiq.pms.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Tag(name = "Audit", description = "Immutable governance trail")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "action", "entityType");

    private final AuditLogRepository repository;

    @Operation(summary = "Search the audit trail")
    @PreAuthorize("@perm.has('audit:read')")
    @GetMapping
    public PageResponse<AuditEntry> search(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Valid PageQuery query) {

        return PageResponse.from(repository.search(TenantContext.requireOrganizationId(), actorId, action,
                entityType, entityId, from, to,
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt"))), AuditEntry::from);
    }

    @Operation(summary = "Get one audit entry with full before/after state")
    @PreAuthorize("@perm.has('audit:read')")
    @GetMapping("/{id}")
    public AuditEntry get(@PathVariable UUID id) {
        AuditLog entry = repository.findById(id)
                .filter(a -> TenantContext.requireOrganizationId().equals(a.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditLog", id));
        return AuditEntry.from(entry);
    }

    public record AuditEntry(UUID id, UUID actorUserId, String actorEmail, String action, String entityType,
                             UUID entityId, Map<String, Object> before, Map<String, Object> after,
                             String outcome, String ipAddress, String correlationId, Instant createdAt) {

        static AuditEntry from(AuditLog log) {
            return new AuditEntry(log.getId(), log.getActorUserId(), log.getActorEmail(), log.getAction(),
                    log.getEntityType(), log.getEntityId(), log.getBeforeState(), log.getAfterState(),
                    log.getOutcome().name(), log.getIpAddress(), log.getCorrelationId(), log.getCreatedAt());
        }
    }
}
