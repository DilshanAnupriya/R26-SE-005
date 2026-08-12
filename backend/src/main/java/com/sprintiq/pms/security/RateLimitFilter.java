package com.sprintiq.pms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprintiq.pms.common.error.ApiError;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.common.web.CorrelationIdFilter;
import com.sprintiq.pms.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Fixed-window counter in Redis. Auth endpoints get a much tighter budget than
 * authenticated API traffic, which blunts credential-stuffing without punishing
 * normal board usage. Redis failures fail *open* — availability of the product
 * matters more than perfect throttling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "rl:";

    private final StringRedisTemplate redis;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.getRateLimit().isEnabled()) return true;
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean authEndpoint = path.startsWith("/api/v1/auth/");
        String identity = TenantContext.userId().map(Object::toString).orElseGet(() -> clientIp(request));
        int limit = authEndpoint
                ? properties.getRateLimit().getAuthRequestsPerMinute()
                : TenantContext.userId().isPresent()
                    ? properties.getRateLimit().getAuthenticatedRequestsPerMinute()
                    : properties.getRateLimit().getAnonymousRequestsPerMinute();

        String key = KEY_PREFIX + (authEndpoint ? "auth:" : "api:") + identity + ":"
                + (System.currentTimeMillis() / 60_000);

        long count;
        try {
            Long incremented = redis.opsForValue().increment(key);
            count = incremented == null ? 1L : incremented;
            if (count == 1L) {
                redis.expire(key, Duration.ofMinutes(2));
            }
        } catch (RuntimeException redisDown) {
            log.warn("Rate limiter unavailable, allowing request: {}", redisDown.getMessage());
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - count)));

        if (count > limit) {
            response.setStatus(ErrorCode.RATE_LIMITED.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "60");
            objectMapper.writeValue(response.getOutputStream(), ApiError.of(ErrorCode.RATE_LIMITED,
                    "Rate limit exceeded. Try again in a minute.", path, CorrelationIdFilter.current()));
            return;
        }
        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
