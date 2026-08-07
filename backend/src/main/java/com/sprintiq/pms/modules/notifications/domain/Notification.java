package com.sprintiq.pms.modules.notifications.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import com.sprintiq.pms.common.domain.EntityType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One thing one person needs to know about.
 *
 * <p>The title and body are written at creation rather than rendered on read. A
 * notification is a statement about a moment — "Priya assigned you MBA-42" — and
 * rendering it later from live data produces "Priya assigned you MBA-42" for a story
 * that has since been renamed, reassigned or deleted. Storing the sentence keeps the
 * inbox honest.</p>
 *
 * <p>{@code readAt} and {@code deliveredAt} are the only mutable fields, and both only
 * ever move from unset to set.</p>
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification extends AppendOnlyTenantEntity {

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    /** Who caused it. Null for system-generated notifications such as risk alerts. */
    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 48, updatable = false)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", length = 1000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", length = 24)
    private EntityType entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    /** Relative path the client navigates to; never an absolute URL. */
    @Column(name = "link", length = 500)
    private String link;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private NotificationPriority priority = NotificationPriority.NORMAL;

    @Column(name = "read_at")
    private Instant readAt;

    /** When it reached a live client. Distinct from read: delivered is not seen. */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }
}
