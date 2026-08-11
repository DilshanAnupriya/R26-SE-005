package com.sprintiq.pms.common.domain;

import java.util.UUID;

/**
 * Anything carrying an {@code organization_id}, whatever its superclass.
 *
 * <p>Two different roots need the tenant column: {@link TenantAwareEntity} for mutable
 * aggregates and {@link AppendOnlyTenantEntity} for immutable records. The stamping
 * listener and the tenant filter care about the column, not the hierarchy, so they key
 * off this interface rather than off either concrete superclass.</p>
 */
public interface TenantScoped {

    UUID getOrganizationId();

    void setOrganizationId(UUID organizationId);
}
