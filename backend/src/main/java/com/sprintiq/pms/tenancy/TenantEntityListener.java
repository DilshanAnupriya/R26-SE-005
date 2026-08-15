package com.sprintiq.pms.tenancy;

import com.sprintiq.pms.common.domain.TenantScoped;
import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.util.UUID;

/**
 * Stamps and then defends {@code organization_id}:
 * <ul>
 *   <li>on insert the column is populated from the request context;</li>
 *   <li>on update a mismatch between the row's tenant and the caller's tenant
 *       aborts the transaction instead of silently writing across tenants.</li>
 * </ul>
 */
public class TenantEntityListener {

    @PrePersist
    public void onPersist(Object candidate) {
        if (!(candidate instanceof TenantScoped entity)) {
            return;
        }
        if (entity.getOrganizationId() == null) {
            TenantContext.organizationId().ifPresent(entity::setOrganizationId);
        } else {
            assertSameTenant(entity.getOrganizationId());
        }
    }


    @PreUpdate
    public void onUpdate(Object candidate) {
        if (candidate instanceof TenantScoped entity) {
            assertSameTenant(entity.getOrganizationId());
        }
    }

    private void assertSameTenant(UUID entityOrganizationId) {
        TenantContext.get().ifPresent(context -> {
            if (context.systemContext()) return;
            if (entityOrganizationId != null && !entityOrganizationId.equals(context.organizationId())) {
                throw new ApplicationException(ErrorCode.TENANT_MISMATCH,
                        "Attempted cross-tenant write was blocked");
            }
        });
    }
}
