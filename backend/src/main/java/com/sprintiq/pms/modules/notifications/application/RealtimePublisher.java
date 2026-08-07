package com.sprintiq.pms.modules.notifications.application;

import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Pushes live updates over STOMP.
 *
 * <p>Every destination is tenant-prefixed — {@code /topic/org.{orgId}.project.{projectId}}
 * — and the subscription interceptor refuses any destination whose organization segment
 * is not the subscriber's. Without that, a broker topic is a hole straight through the
 * tenancy model that the Hibernate filter cannot see.</p>
 *
 * <p>Delivery is best-effort by design. A dropped frame means a board is briefly stale,
 * and the client reconciles on its next fetch; failing the business operation because a
 * websocket was unreachable would be a much worse trade. Failures are logged, never
 * thrown.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimePublisher {

    public static final String PROJECT_TOPIC = "/topic/org.%s.project.%s";
    public static final String SPRINT_TOPIC = "/topic/org.%s.sprint.%s";
    public static final String USER_QUEUE = "/queue/notifications";

    private final SimpMessagingTemplate messagingTemplate;

    /** An item changed on a board someone is watching. */
    public void projectEvent(UUID projectId, String event, Object payload) {
        TenantContext.organizationId().ifPresent(organizationId ->
                send(PROJECT_TOPIC.formatted(organizationId, projectId), event, payload));
    }

    public void sprintEvent(UUID sprintId, String event, Object payload) {
        TenantContext.organizationId().ifPresent(organizationId ->
                send(SPRINT_TOPIC.formatted(organizationId, sprintId), event, payload));
    }

    /**
     * A notification for one person.
     *
     * <p>Sent to the user destination rather than a per-user topic so Spring resolves it
     * against that user's active sessions — a person logged in on a laptop and a phone
     * gets it on both, and someone who is offline simply does not, without the publisher
     * needing to know either fact.</p>
     */
    public void toUser(UUID userId, String event, Object payload) {
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), USER_QUEUE,
                    envelope(event, payload));
        } catch (RuntimeException ex) {
            log.warn("Realtime delivery to user {} failed: {}", userId, ex.toString());
        }
    }

    private void send(String destination, String event, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, envelope(event, payload));
        } catch (RuntimeException ex) {
            log.warn("Realtime publish to {} failed: {}", destination, ex.toString());
        }
    }

    /** Every frame carries its event name, so a client can switch on one field. */
    private Map<String, Object> envelope(String event, Object payload) {
        return Map.of("event", event, "at", java.time.Instant.now().toString(), "data", payload);
    }
}
