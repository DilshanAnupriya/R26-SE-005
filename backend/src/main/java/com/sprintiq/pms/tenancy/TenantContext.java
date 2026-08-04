package com.sprintiq.pms.tenancy;

import com.sprintiq.pms.common.error.TenantNotResolvedException;
import lombok.Builder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-request tenant + principal context. Backed by an inheritable holder so that
 * {@code @Async} work started from a request thread keeps the same organization
 * (see {@link TenantTaskDecorator}).
 *
 * <p>Reads are fail-closed: {@link #requireOrganizationId()} throws rather than
 * silently returning null, so a forgotten context can never widen a query.</p>
 */
public final class TenantContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    @Builder
    public record Context(UUID organizationId,
                          UUID userId,
                          String userEmail,
                          Set<String> permissions,
                          String roleCode,
                          boolean systemContext) {}

    public static void set(Context context) {
        HOLDER.set(context);
    }

    public static Optional<Context> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Optional<UUID> organizationId() {
        return get().map(Context::organizationId);
    }

    public static UUID requireOrganizationId() {
        return organizationId().orElseThrow(() ->
                new TenantNotResolvedException("No organization is bound to the current request"));
    }

    public static Optional<UUID> userId() {
        return get().map(Context::userId);
    }

    public static UUID requireUserId() {
        return userId().orElseThrow(() ->
                new TenantNotResolvedException("No authenticated user is bound to the current request"));
    }

    public static boolean hasPermission(String permission) {
        return get().map(c -> c.systemContext() || c.permissions().contains(permission)).orElse(false);
    }

    /** Runs a unit of work under an explicit tenant — used by schedulers and seeders. */
    public static void runAs(UUID organizationId, UUID userId, Runnable work) {
        Context previous = HOLDER.get();
        try {
            HOLDER.set(Context.builder()
                    .organizationId(organizationId)
                    .userId(userId)
                    .permissions(Set.of())
                    .systemContext(true)
                    .build());
            work.run();
        } finally {
            if (previous != null) HOLDER.set(previous); else HOLDER.remove();
        }
    }
}
