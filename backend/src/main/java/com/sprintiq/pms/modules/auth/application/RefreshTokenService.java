package com.sprintiq.pms.modules.auth.application;

import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.common.util.TokenHasher;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.audit.domain.AuditAction;
import com.sprintiq.pms.modules.audit.domain.AuditOutcome;
import com.sprintiq.pms.modules.auth.domain.RefreshToken;
import com.sprintiq.pms.modules.auth.domain.RefreshTokenRepository;
import com.sprintiq.pms.security.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refresh-token rotation with reuse detection.
 *
 * <p>Each login starts a token "family". Every refresh revokes the presented token and
 * issues a successor in the same family. If a token that has already been rotated is
 * presented again, either it was stolen or the legitimate client is replaying — either
 * way we revoke the entire family, forcing a fresh login.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final AppProperties properties;
    private final AuditService auditService;

    /**
     * The raw value only exists for the length of the request that created it; it is never
     * persisted. This map carries it from rotation back to the controller response.
     */
    private final Map<UUID, String> rawTokenBuffer = new ConcurrentHashMap<>();

    @Transactional
    public String issue(UUID userId, UUID organizationId, HttpServletRequest request) {
        return issueInFamily(userId, organizationId, UUID.randomUUID(), request);
    }

    @Transactional
    public RefreshToken verifyAndRotate(String rawToken, HttpServletRequest request) {
        RefreshToken presented = repository.findByTokenHash(TokenHasher.hash(rawToken))
                .orElseThrow(() -> new ApplicationException(ErrorCode.TOKEN_INVALID,
                        "Refresh token is not recognised"));

        if (presented.isRevoked()) {
            log.warn("Refresh token reuse detected for user {} (family {})",
                    presented.getUserId(), presented.getFamilyId());
            repository.revokeFamily(presented.getFamilyId(), Instant.now());
            auditService.recordOutcome(AuditAction.TOKEN_REUSE_DETECTED, "RefreshToken", presented.getId(),
                    AuditOutcome.DENIED, Map.of("userId", presented.getUserId().toString()));
            throw new ApplicationException(ErrorCode.TOKEN_INVALID,
                    "Session was revoked for security reasons. Please sign in again.");
        }
        if (presented.isExpired()) {
            throw new ApplicationException(ErrorCode.TOKEN_EXPIRED, "Session expired. Please sign in again.");
        }

        String rawSuccessor = issueInFamily(presented.getUserId(), presented.getOrganizationId(),
                presented.getFamilyId(), request);
        RefreshToken successor = repository.findByTokenHash(TokenHasher.hash(rawSuccessor)).orElseThrow();

        presented.setRevokedAt(Instant.now());
        presented.setReplacedBy(successor.getId());
        repository.save(presented);

        rawTokenBuffer.put(successor.getId(), rawSuccessor);
        return successor;
    }

    /** Retrieves (and clears) the raw value produced by {@link #verifyAndRotate}. */
    public String rawValueOf(RefreshToken token) {
        String raw = rawTokenBuffer.remove(token.getId());
        if (raw == null) {
            throw new IllegalStateException("Raw refresh token requested outside its issuing request");
        }
        return raw;
    }

    @Transactional
    public void revokeByRawToken(String rawToken) {
        repository.findByTokenHash(TokenHasher.hash(rawToken)).ifPresent(token -> {
            token.setRevokedAt(Instant.now());
            repository.save(token);
        });
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        repository.revokeAllForUser(userId, Instant.now());
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        int removed = repository.deleteExpired(Instant.now().minus(properties.getSecurity()
                .getJwt().getRefreshTokenTtl()));
        if (removed > 0) {
            log.info("Purged {} expired refresh tokens", removed);
        }
    }

    private String issueInFamily(UUID userId, UUID organizationId, UUID familyId, HttpServletRequest request) {
        String raw = TokenHasher.generateToken();
        RefreshToken token = RefreshToken.builder()
                .userId(userId)
                .organizationId(organizationId)
                .familyId(familyId)
                .tokenHash(TokenHasher.hash(raw))
                .expiresAt(Instant.now().plus(properties.getSecurity().getJwt().getRefreshTokenTtl()))
                .userAgent(truncate(request == null ? null : request.getHeader("User-Agent")))
                .ipAddress(request == null ? null : request.getRemoteAddr())
                .build();
        repository.save(token);
        return raw;
    }

    private String truncate(String value) {
        return value == null || value.length() <= 255 ? value : value.substring(0, 255);
    }
}
