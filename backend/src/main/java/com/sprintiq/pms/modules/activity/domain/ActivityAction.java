package com.sprintiq.pms.modules.activity.domain;

/** Canonical activity verbs. Stable values so the UI can map them to icons and phrasing. */
public final class ActivityAction {

    private ActivityAction() {}

    public static final String CREATED = "CREATED";
    public static final String UPDATED = "UPDATED";
    public static final String DELETED = "DELETED";
    public static final String RESTORED = "RESTORED";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String UNASSIGNED = "UNASSIGNED";
    public static final String ESTIMATED = "ESTIMATED";
    public static final String RANKED = "RANKED";
    public static final String MOVED_TO_SPRINT = "MOVED_TO_SPRINT";
    public static final String REMOVED_FROM_SPRINT = "REMOVED_FROM_SPRINT";
    public static final String BLOCKED = "BLOCKED";
    public static final String UNBLOCKED = "UNBLOCKED";
    public static final String DEPENDENCY_ADDED = "DEPENDENCY_ADDED";
    public static final String DEPENDENCY_REMOVED = "DEPENDENCY_REMOVED";
    public static final String LABEL_ADDED = "LABEL_ADDED";
    public static final String LABEL_REMOVED = "LABEL_REMOVED";
    public static final String TIME_LOGGED = "TIME_LOGGED";
    public static final String SPRINT_STARTED = "SPRINT_STARTED";
    public static final String SPRINT_COMPLETED = "SPRINT_COMPLETED";
    public static final String SPRINT_CANCELLED = "SPRINT_CANCELLED";
    public static final String REOPENED = "REOPENED";
}
