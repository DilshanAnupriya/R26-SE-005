package com.sprintiq.pms.modules.notifications.web.dto;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.notifications.domain.Notification;
import com.sprintiq.pms.modules.notifications.domain.NotificationPriority;
import com.sprintiq.pms.modules.notifications.domain.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    @Schema(name = "NotificationResponse")
    public record NotificationResponse(UUID id,
                                       NotificationType type,
                                       String title,
                                       String body,
                                       EntityType entityType,
                                       UUID entityId,
                                       String link,
                                       NotificationPriority priority,
                                       UUID actorId,
                                       String actorName,
                                       String actorAvatarUrl,
                                       boolean read,
                                       Instant readAt,
                                       Instant createdAt) {

        public static NotificationResponse from(Notification notification, String actorName,
                                                String actorAvatarUrl) {
            return new NotificationResponse(notification.getId(), notification.getType(),
                    notification.getTitle(), notification.getBody(), notification.getEntityType(),
                    notification.getEntityId(), notification.getLink(), notification.getPriority(),
                    notification.getActorId(), actorName, actorAvatarUrl,
                    notification.isRead(), notification.getReadAt(), notification.getCreatedAt());
        }
    }

    @Schema(name = "UnreadCountResponse")
    public record UnreadCountResponse(long unread) {}

    @Schema(name = "NotificationPreferenceResponse")
    public record NotificationPreferenceResponse(NotificationType type,
                                                 String description,
                                                 boolean inApp,
                                                 boolean email,
                                                 boolean digest) {}

    @Schema(name = "NotificationPreferenceEntry")
    public record NotificationPreferenceEntry(@NotNull NotificationType type,
                                              @NotNull Boolean inApp,
                                              @NotNull Boolean email,
                                              Boolean digest) {}

    @Schema(name = "UpdateNotificationPreferencesRequest")
    public record UpdateNotificationPreferencesRequest(
            @NotNull List<@Valid NotificationPreferenceEntry> preferences) {}
}
