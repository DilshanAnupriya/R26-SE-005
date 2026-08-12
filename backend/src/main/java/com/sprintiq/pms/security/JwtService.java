package com.sprintiq.pms.security;

import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies short-lived access tokens. Refresh tokens are opaque random
 * values persisted (hashed) by {@code RefreshTokenService} — they are never JWTs,
 * so they can be revoked server-side.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String CLAIM_ORGANIZATION = "org";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_PERMISSIONS = "perms";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_TYPE = "typ";

    private final AppProperties properties;
    private SecretKey signingKey;

    private SecretKey key() {
        if (signingKey == null) {
            String secret = properties.getSecurity().getJwt().getSecret();
            byte[] material;
            try {
                material = Base64.getDecoder().decode(secret);
            } catch (IllegalArgumentException notBase64) {
                material = secret.getBytes(StandardCharsets.UTF_8);
            }
            if (material.length < 32) {
                throw new IllegalStateException("app.security.jwt.secret must be at least 256 bits");
            }
            signingKey = Keys.hmacShaKeyFor(material);
        }
        return signingKey;
    }

    public String issueAccessToken(UUID userId, String email, String fullName, UUID organizationId,
                                   String roleCode, Set<String> permissions) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getSecurity().getJwt().getAccessTokenTtl());
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(properties.getSecurity().getJwt().getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TYPE, "access")
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_NAME, fullName)
                .claim(CLAIM_ORGANIZATION, organizationId != null ? organizationId.toString() : null)
                .claim(CLAIM_ROLE, roleCode)
                .claim(CLAIM_PERMISSIONS, permissions)
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key())
                    .requireIssuer(properties.getSecurity().getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new ApplicationException(ErrorCode.TOKEN_EXPIRED, "Access token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            throw new ApplicationException(ErrorCode.TOKEN_INVALID, "Access token is invalid");
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.getSecurity().getJwt().getAccessTokenTtl().toSeconds();
    }
}
