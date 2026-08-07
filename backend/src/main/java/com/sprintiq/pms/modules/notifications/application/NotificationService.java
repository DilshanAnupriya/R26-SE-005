package com.sprintiq.pms.modules.notifications.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.AccessDeniedAppException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.notifications.domain.*;
import com.sprintiq.pms.modules.notifications.web.dto.NotificationDtos.*;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Creates, delivers and reads notifications.
 *
 * <p>The producing modules — comments, tasks, sprints, AI — call {@link #notify} and are
 * done. Preference resolution, self-notification suppression and realtime delivery all
 * happen here, so a new notification source cannot accidentally ship without honouring
 * someone's settings.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final Set<String> SORT_FIELDS = Set.of("createdAt", "priority", "type", "readAt");

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final RealtimePublisher realtime;
    private final UserService userService;

    /** Everything a caller needs to describe one notification. */
    public record Notify(UUID recipientId,
                         NotificationType type,
                         String title,
                         String body,
                         EntityType entityType,
                         UUID entityId,
                         String link) {}

    /**
     * Delivers to one person, unless they are the person who caused it.
     *
     * <p>Self-suppression is here rather than in each caller because it is the single
     * most common way notification systems become annoying, and the caller that forgets
     * is always the one shipping at 6pm on a Friday.</p>
     */
    @Transactional
    public Optional<Notification> notify(Notify request) {
        UUID actorId = TenantContext.userId().orElse(null);
        if (request.recipientId() == null || request.recipientId().equals(actorId)) {
            return Optional.empty();
        }
        if (!wantsInApp(request.recipientId(), request.type())) {
            return Optional.empty();
        }

        Notification notification = Notification.builder()
                .recipientId(request.recipientId())
                .actorId(actorId)
                .type(request.type())
                .title(truncate(request.title(), 200))
                .body(truncate(request.body(), 1000))
                .entityType(request.entityType())
                .entityId(request.entityId())
                .link(request.link())
                .priority(request.type().getDefaultPriority())
                .build();
        notification.setOrganizationId(TenantContext.requireOrganizationId());
        Notification saved = notifications.save(notification);

        realtime.toUser(request.recipientId(), "notification.created",
                toResponse(saved, Map.of()));
        return Optional.of(saved);
    }

    /** Fan-out to several people, de-duplicated. */
    @Transactional
    public void notifyAll(Collection<UUID> recipientIds, NotificationType type, String title,
                          String body, EntityType entityType, UUID entityId, String link) {
        // A person who is both watching an item and mentioned in the same comment should
        // get one notification, not two.
        for (UUID recipientId : new LinkedHashSet<>(recipientIds)) {
            notify(new Notify(recipientId, type, title, body, entityType, entityId, link));
        }
    }

    // -------------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> inbox(boolean unreadOnly, NotificationType type,
                                                    PageQuery query) {
        UUID userId = TenantContext.requireUserId();
        Page<Notification> page = notifications.findForRecipient(userId, unreadOnly, type,
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<UUID, UserSummary> actors = userService.summariesFor(page.getContent().stream()
                .map(Notification::getActorId).filter(Objects::nonNull).toList());

        return PageResponse.from(page, notification -> toResponse(notification, actors));
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(
                notifications.countByRecipientIdAndReadAtIsNull(TenantContext.requireUserId()));
    }

    /**
     * Marks one notification read.
     *
     * <p>Ownership is checked explicitly. The tenant filter narrows to the organization,
     * which would still let one colleague mark another's notification read.</p>
     */
    @Transactional
    public NotificationResponse markRead(UUID notificationId) {
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", notificationId));
        if (!notification.getRecipientId().equals(TenantContext.requireUserId())) {
            throw AccessDeniedAppException.missingPermission("notification:read-own");
        }
        notification.markRead();
        return toResponse(notifications.save(notification), Map.of());
    }

    @Transactional
    public UnreadCountResponse markAllRead() {
        notifications.markAllRead(TenantContext.requireUserId(), Instant.now());
        return new UnreadCountResponse(0);
    }

    // ---------------------------------------------------------------- preferences

    /** Every known type with its effective setting, so the settings page renders in full. */
    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> preferencesOf() {
        UUID userId = TenantContext.requireUserId();
        Map<NotificationType, NotificationPreference> stored = new EnumMap<>(NotificationType.class);
        preferences.findByUserId(userId)
                .forEach(preference -> stored.put(preference.getNotificationType(), preference));

        List<NotificationPreferenceResponse> resolved = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            NotificationPreference preference = stored.get(type);
            resolved.add(new NotificationPreferenceResponse(type, type.getDescription(),
                    preference == null || preference.isInApp(),
                    preference == null || preference.isEmail(),
                    preference != null && preference.isDigest()));
        }
        return resolved;
    }

    @Transactional
    public List<NotificationPreferenceResponse> updatePreferences(
            UpdateNotificationPreferencesRequest request) {
        UUID userId = TenantContext.requireUserId();
        for (NotificationPreferenceEntry entry : request.preferences()) {
            NotificationPreference preference = preferences
                    .findByUserIdAndNotificationType(userId, entry.type())
                    .orElseGet(() -> NotificationPreference.builder()
                            .userId(userId).notificationType(entry.type()).build());
            preference.setInApp(entry.inApp());
            preference.setEmail(entry.email());
            preference.setDigest(entry.digest() != null && entry.digest());
            preferences.save(preference);
        }
        return preferencesOf();
    }

    // ------------------------------------------------------------------ internals

    private boolean wantsInApp(UUID recipientId, NotificationType type) {
        return preferences.findByUserIdAndNotificationType(recipientId, type)
                .map(NotificationPreference::isInApp)
                .orElse(true);
    }

    private NotificationResponse toResponse(Notification notification,
                                            Map<UUID, UserSummary> actors) {
        UserSummary actor = notification.getActorId() == null ? null
                : actors.get(notification.getActorId());
        return NotificationResponse.from(notification,
                actor == null ? null : actor.fullName(),
                actor == null ? null : actor.avatarUrl());
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
