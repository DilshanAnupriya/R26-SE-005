package com.sprintiq.pms.modules.notifications.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What a notification is about. The list is deliberately short.
 *
 * <p>Every type here is something that happened to <em>you</em> or to work you own:
 * someone named you, assigned you something, replied to you, or a sprint you are on
 * crossed a risk threshold. Notifying on general activity — every status change, every
 * new story — produces a feed people mute in week one, after which the mention that
 * mattered goes unread too.</p>
 */
@Getter
@RequiredArgsConstructor
public enum NotificationType {

    MENTION("You were mentioned", NotificationPriority.HIGH),
    ASSIGNED("Work assigned to you", NotificationPriority.HIGH),
    UNASSIGNED("Work taken off you", NotificationPriority.NORMAL),
    COMMENT("New comment on your item", NotificationPriority.NORMAL),
    COMMENT_REPLY("Reply to your comment", NotificationPriority.NORMAL),
    STATUS_CHANGED("Status changed on an item you watch", NotificationPriority.LOW),
    BLOCKED("Work you own is blocked", NotificationPriority.HIGH),
    DUE_SOON("Work you own is due", NotificationPriority.NORMAL),
    SPRINT_STARTED("A sprint you are on has started", NotificationPriority.NORMAL),
    SPRINT_COMPLETED("A sprint you are on has completed", NotificationPriority.NORMAL),
    SPRINT_RISK("A sprint you are on is at risk", NotificationPriority.HIGH),
    COST_ALERT("A sprint forecast has moved past its budget", NotificationPriority.HIGH),
    MENTION_IN_DESCRIPTION("You were mentioned in a description", NotificationPriority.NORMAL),
    INVITATION("You were invited to an organization", NotificationPriority.HIGH);

    private final String description;
    private final NotificationPriority defaultPriority;
}
