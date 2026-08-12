package com.sprintiq.pms.modules.audit.application;

import com.sprintiq.pms.common.web.CorrelationIdFilter;
import com.sprintiq.pms.modules.audit.domain.AuditLog;
import com.sprintiq.pms.modules.audit.domain.AuditLogRepository;
import com.sprintiq.pms.modules.audit.domain.AuditOutcome;
import com.sprintiq.pms.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;

/**
 * Writes the governance trail. Runs asynchronously in a REQUIRES_NEW transaction so a
 * failed audit write can never roll back the business operation it describes — and a
 * failed business operation still leaves its DENIED/FAILURE record behind.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;

    @Async("eventExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, UUID entityId,
                       Map<String, Object> before, Map<String, Object> after) {
        write(action, entityType, entityId, before, after, AuditOutcome.SUCCESS, Map.of());
    }

    @Async("eventExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(String action, String entityType, UUID entityId,
                              AuditOutcome outcome, Map<String, Object> metadata) {
        write(action, entityType, entityId, null, null, outcome, metadata);
    }

    /** Used by pre-authentication flows (failed login, reset requests) where no context exists yet. */
    @Async("eventExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAnonymous(String action, String actorEmail, UUID organizationId,
                                AuditOutcome outcome, Map<String, Object> metadata) {
        try {
            AuditLog entry = baseEntry(action, outcome)
                    .toBuilder()
                    .organizationId(organizationId)
                    .actorEmail(actorEmail)
                    .metadata(metadata == null ? Map.of() : metadata)
                    .build();
            repository.save(entry);
        } catch (RuntimeException ex) {
            log.error("Failed to persist anonymous audit entry {}", action, ex);
        }
    }

    private void write(String action, String entityType, UUID entityId,
                       Map<String, Object> before, Map<String, Object> after,
                       AuditOutcome outcome, Map<String, Object> metadata) {
        try {
            AuditLog entry = baseEntry(action, outcome).toBuilder()
                    .organizationId(TenantContext.organizationId().orElse(null))
                    .actorUserId(TenantContext.userId().orElse(null))
                    .actorEmail(TenantContext.get().map(TenantContext.Context::userEmail).orElse(null))
                    .entityType(entityType)
                    .entityId(entityId)
                    .beforeState(before)
                    .afterState(after)
                    .metadata(metadata == null ? Map.of() : metadata)
                    .build();
            repository.save(entry);
        } catch (RuntimeException ex) {
            log.error("Failed to persist audit entry {} for {}#{}", action, entityType, entityId, ex);
        }
    }

    private AuditLog baseEntry(String action, AuditOutcome outcome) {
        AuditLog.AuditLogBuilder builder = AuditLog.builder()
                .action(action)
                .outcome(outcome)
                .correlationId(CorrelationIdFilter.current());

        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            builder.ipAddress(clientIp(request));
            String agent = request.getHeader("User-Agent");
            builder.userAgent(agent != null && agent.length() > 255 ? agent.substring(0, 255) : agent);
        }
        return builder.build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
    }
}
