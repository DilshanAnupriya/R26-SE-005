package com.sprintiq.pms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprintiq.pms.common.error.ApiError;
import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.CorrelationIdFilter;
import com.sprintiq.pms.tenancy.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates the bearer token, builds the {@link AppPrincipal} and — critically —
 * binds the tenant context for the request. Authentication and tenant resolution
 * happen together so no code path can ever be authenticated but tenant-less.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(ApiConstants.BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.parse(header.substring(ApiConstants.BEARER_PREFIX.length()).trim());
            AppPrincipal principal = toPrincipal(claims);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            TenantContext.set(TenantContext.Context.builder()
                    .organizationId(principal.organizationId())
                    .userId(principal.userId())
                    .userEmail(principal.email())
                    .roleCode(principal.roleCode())
                    .permissions(principal.permissions())
                    .systemContext(false)
                    .build());

            chain.doFilter(request, response);
        } catch (ApplicationException ex) {
            SecurityContextHolder.clearContext();
            writeError(response, request, ex);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @SuppressWarnings("unchecked")
    private AppPrincipal toPrincipal(Claims claims) {
        Object rawPermissions = claims.get(JwtService.CLAIM_PERMISSIONS);
        Set<String> permissions = rawPermissions instanceof List<?> list
                ? new HashSet<>((List<String>) list)
                : Set.of();
        String organization = claims.get(JwtService.CLAIM_ORGANIZATION, String.class);

        return AppPrincipal.builder()
                .userId(UUID.fromString(claims.getSubject()))
                .email(claims.get(JwtService.CLAIM_EMAIL, String.class))
                .fullName(claims.get(JwtService.CLAIM_NAME, String.class))
                .organizationId(organization != null ? UUID.fromString(organization) : null)
                .roleCode(claims.get(JwtService.CLAIM_ROLE, String.class))
                .permissions(permissions)
                .enabled(true)
                .build();
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request,
                            ApplicationException ex) throws IOException {
        response.setStatus(ex.getErrorCode().getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(ex.getErrorCode(), ex.getMessage(),
                request.getRequestURI(), CorrelationIdFilter.current());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
