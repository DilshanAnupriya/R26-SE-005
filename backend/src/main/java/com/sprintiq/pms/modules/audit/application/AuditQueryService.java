package com.sprintiq.pms.modules.audit.application;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.audit.domain.AuditLog;
import com.sprintiq.pms.modules.audit.domain.AuditLogRepository;
import com.sprintiq.pms.modules.audit.web.dto.AuditDtos.AuditEntryResponse;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Read side of the audit trail.
 *
 * <p>Split out from the controller so the web layer neither holds a repository nor
 * touches the {@code AuditLog} entity — both of which the architecture rules forbid, and
 * both of which quietly leak the persistence model into the API's shape.</p>
 *
 * <p>The write side stays in {@link AuditService}, which is async and transaction-isolated
 * for reasons that do not apply to reads.</p>
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    public static final Set<String> SORT_FIELDS = Set.of("createdAt", "action", "entityType");

    private final AuditLogRepository repository;

    @Transactional(readOnly = true)
    public PageResponse<AuditEntryResponse> search(UUID actorId, String action, String entityType,
                                                   UUID entityId, Instant from, Instant to, PageQuery query) {
        return PageResponse.from(
                repository.search(TenantContext.requireOrganizationId(), actorId, action, entityType,
                        entityId, from, to,
                        query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt"))),
                AuditQueryService::toResponse);
    }

    @Transactional(readOnly = true)
    public AuditEntryResponse get(UUID id) {
        AuditLog entry = repository.findById(id)
                // The tenant filter does not reach AuditLog, so scoping is asserted here.
                .filter(log -> TenantContext.requireOrganizationId().equals(log.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditLog", id));
        return toResponse(entry);
    }

    private static AuditEntryResponse toResponse(AuditLog log) {
        return new AuditEntryResponse(log.getId(), log.getActorUserId(), log.getActorEmail(),
                log.getAction(), log.getEntityType(), log.getEntityId(), log.getBeforeState(),
                log.getAfterState(), log.getOutcome().name(), log.getIpAddress(), log.getCorrelationId(),
                log.getCreatedAt());
    }
}
