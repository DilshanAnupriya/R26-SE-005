package com.sprintiq.pms.config;

import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.security.AppPrincipal;
import com.sprintiq.pms.security.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates the STOMP CONNECT frame and refuses cross-tenant subscriptions.
 *
 * <p>The message broker is outside the servlet filter chain, so none of the HTTP
 * security applies to it. Without this interceptor the websocket would be an
 * unauthenticated door into every board update in every organization — and unlike a REST
 * leak, the Hibernate tenant filter cannot help, because the broker never touches the
 * database.</p>
 *
 * <p>Two checks, both necessary. CONNECT establishes who you are from the same bearer
 * token the REST API uses. SUBSCRIBE checks that the destination's organization segment
 * is your organization: a valid token for tenant A must not be able to subscribe to
 * {@code /topic/org.B.project.X}, and topic names are attacker-supplied strings.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    /** Destinations are {@code /topic/org.{organizationId}.<rest>}. */
    private static final String TENANT_PREFIX = "/topic/org.";

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = first(accessor.getNativeHeader("Authorization"));
        if (header == null || !header.startsWith("Bearer ")) {
            throw new IllegalArgumentException("A websocket connection requires a bearer token");
        }
        try {
            Claims claims = jwtService.parse(header.substring(7));
            AppPrincipal principal = toPrincipal(claims);
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities()));
        } catch (ApplicationException ex) {
            // Surfaced to the client as a STOMP ERROR frame; the connection never opens.
            throw new IllegalArgumentException(ex.getMessage());
        }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TENANT_PREFIX)) {
            // User destinations (/user/queue/...) are routed by Spring against the
            // authenticated principal, so they need no check of their own.
            return;
        }
        if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken token)
                || !(token.getPrincipal() instanceof AppPrincipal principal)) {
            throw new IllegalArgumentException("Not authenticated");
        }

        String remainder = destination.substring(TENANT_PREFIX.length());
        int separator = remainder.indexOf('.');
        String organizationSegment = separator < 0 ? remainder : remainder.substring(0, separator);

        if (principal.organizationId() == null
                || !principal.organizationId().toString().equals(organizationSegment)) {
            log.warn("Refused cross-tenant subscription to {} by user {}", destination,
                    principal.userId());
            throw new IllegalArgumentException("Subscription denied");
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
                .organizationId(organization == null ? null : UUID.fromString(organization))
                .roleCode(claims.get(JwtService.CLAIM_ROLE, String.class))
                .permissions(permissions)
                .enabled(true)
                .build();
    }

    private String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
