package com.sprintiq.pms.common.error;

public class AccessDeniedAppException extends ApplicationException {
    public AccessDeniedAppException(String message) {
        super(ErrorCode.ACCESS_DENIED, message);
    }

    public static AccessDeniedAppException missingPermission(String permission) {
        return new AccessDeniedAppException("Missing required permission: " + permission);
    }
}
