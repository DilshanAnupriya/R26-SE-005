package com.sprintiq.pms.modules.audit.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.audit.application.AuditQueryService;
import com.sprintiq.pms.modules.audit.web.dto.AuditDtos.AuditEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Audit", description = "Immutable governance trail")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditQueryService auditQueryService;

    @Operation(summary = "Search the audit trail")
    @PreAuthorize("@perm.has('audit:read')")
    @GetMapping
    public PageResponse<AuditEntryResponse> search(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Valid PageQuery query) {
        return auditQueryService.search(actorId, action, entityType, entityId, from, to, query);
    }

    @Operation(summary = "Get one audit entry with full before/after state")
    @PreAuthorize("@perm.has('audit:read')")
    @GetMapping("/{id}")
    public AuditEntryResponse get(@PathVariable UUID id) {
        return auditQueryService.get(id);
    }
}
