package com.sprintiq.pms.modules.notifications.domain;

/**
 * How loudly to deliver. {@code URGENT} is reserved for things that are already going
 * wrong and is never assigned automatically — an automatic urgent tier stops being
 * urgent within a fortnight.
 */
public enum NotificationPriority {

    LOW,
    NORMAL,
    HIGH,
    URGENT
}
