package com.sprintiq.pms.modules.notifications.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One person's delivery choices for one notification type, in one organization.
 *
 * <p>Scoped per organization rather than per user because someone consulting for three
 * clients wants email from the one paying for their attention this month and in-app only
 * from the others.</p>
 *
 * <p>A missing row means the type's defaults apply. Provisioning fourteen rows per person
 * per organization at signup would make adding a notification type a backfill migration.</p>
 */
@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreference extends TenantAwareEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 48, updatable = false)
    private NotificationType notificationType;

    @Builder.Default
    @Column(name = "in_app", nullable = false)
    private boolean inApp = true;

    @Builder.Default
    @Column(name = "email", nullable = false)
    private boolean email = true;

    /** Batch into a daily summary instead of sending each one. */
    @Builder.Default
    @Column(name = "digest", nullable = false)
    private boolean digest = false;
}
