package com.sprintiq.pms.security;

import com.sprintiq.pms.common.error.AccessDeniedAppException;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Exposed to SpEL as {@code @perm} so controllers and services read declaratively:
 * <pre>{@code @PreAuthorize("@perm.has('sprint:manage')")}</pre>
 * Keeping this in one bean means the permission vocabulary has exactly one enforcement point.
 */
@Slf4j
@Component("perm")
public class PermissionService {

    public boolean isSelf(UUID userId) {
        return userId != null && TenantContext.userId().map(userId::equals).orElse(false);
    }

    public boolean has(String permission) {
        return TenantContext.hasPermission(permission);
    }

    public boolean hasAny(String... permissions) {
        for (String permission : permissions) {
            if (TenantContext.hasPermission(permission)) return true;
        }
        return false;
    }

    public boolean hasAll(String... permissions) {
        for (String permission : permissions) {
            if (!TenantContext.hasPermission(permission)) return false;
        }
        return true;
    }

    /** Imperative variant for service-layer guards that need a specific message. */
    public void require(String permission) {
        if (!has(permission)) {
            throw AccessDeniedAppException.missingPermission(permission);
        }
    }
}
