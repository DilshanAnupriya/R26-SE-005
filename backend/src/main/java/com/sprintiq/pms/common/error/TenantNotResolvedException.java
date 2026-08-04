package com.sprintiq.pms.common.error;

/** Thrown when tenant-scoped work is attempted without an organization context (fail-closed). */
public class TenantNotResolvedException extends ApplicationException {
    public TenantNotResolvedException() {
        super(ErrorCode.TENANT_NOT_RESOLVED);
    }

    public TenantNotResolvedException(String message) {
        super(ErrorCode.TENANT_NOT_RESOLVED, message);
    }
}
