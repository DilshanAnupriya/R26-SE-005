package com.sprintiq.pms.modules.notifications.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipientId = :recipientId
              AND (:unreadOnly = FALSE OR n.readAt IS NULL)
              AND (:type IS NULL OR n.type = :type)
            """)
    Page<Notification> findForRecipient(@Param("recipientId") UUID recipientId,
                                        @Param("unreadOnly") boolean unreadOnly,
                                        @Param("type") NotificationType type,
                                        Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(UUID recipientId);

    /**
     * Bulk mark-as-read as a single statement.
     *
     * <p>Loading an inbox of two hundred rows to set one timestamp on each is the kind
     * of thing that turns "mark all read" into a five-second button. The persistence
     * context is cleared afterwards so a caller holding stale entities cannot write the
     * old {@code readAt} back over this.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n SET n.readAt = :readAt
            WHERE n.recipientId = :recipientId AND n.readAt IS NULL
            """)
    int markAllRead(@Param("recipientId") UUID recipientId, @Param("readAt") Instant readAt);

    List<Notification> findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(UUID recipientId,
                                                                           Pageable pageable);
}
