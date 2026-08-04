package com.sprintiq.pms.modules.audit.domain;

/** Canonical audit verbs. Kept as constants so dashboards can filter on stable values. */
public final class AuditAction {

    private AuditAction() {}

    public static final String USER_REGISTERED = "USER_REGISTERED";
    public static final String USER_LOGIN = "USER_LOGIN";
    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";
    public static final String USER_LOGOUT = "USER_LOGOUT";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    public static final String TOKEN_REUSE_DETECTED = "TOKEN_REUSE_DETECTED";
    public static final String ORGANIZATION_CREATED = "ORGANIZATION_CREATED";
    public static final String ORGANIZATION_UPDATED = "ORGANIZATION_UPDATED";
    public static final String WORKSPACE_CREATED = "WORKSPACE_CREATED";
    public static final String MEMBER_INVITED = "MEMBER_INVITED";
    public static final String MEMBER_JOINED = "MEMBER_JOINED";
    public static final String MEMBER_ROLE_CHANGED = "MEMBER_ROLE_CHANGED";
    public static final String MEMBER_REMOVED = "MEMBER_REMOVED";
    public static final String ROLE_CREATED = "ROLE_CREATED";
    public static final String ROLE_UPDATED = "ROLE_UPDATED";
    public static final String PROJECT_CREATED = "PROJECT_CREATED";
    public static final String PROJECT_DELETED = "PROJECT_DELETED";
    public static final String SPRINT_STARTED = "SPRINT_STARTED";
    public static final String SPRINT_COMPLETED = "SPRINT_COMPLETED";
    public static final String AI_PREDICTION_REQUESTED = "AI_PREDICTION_REQUESTED";
    public static final String AI_SUGGESTION_ACCEPTED = "AI_SUGGESTION_ACCEPTED";
    public static final String AI_SUGGESTION_OVERRIDDEN = "AI_SUGGESTION_OVERRIDDEN";
    public static final String SETTINGS_UPDATED = "SETTINGS_UPDATED";
    public static final String DATA_EXPORTED = "DATA_EXPORTED";
}
