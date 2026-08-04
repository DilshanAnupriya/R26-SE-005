package com.sprintiq.pms.common.domain;

import com.sprintiq.pms.tenancy.TenantEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.UUID;

/**
 * Every tenant-scoped table carries {@code organization_id}. The Hibernate filter
 * declared here is switched on for each session by {@code TenantFilterAspect},
 * so all queries are automatically narrowed to the caller's organization.
 * The column is stamped on persist by {@link TenantEntityListener} — application
 * code never sets it manually, which removes an entire class of leakage bugs.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(TenantEntityListener.class)
@FilterDef(name = TenantAwareEntity.TENANT_FILTER,
        parameters = @ParamDef(name = TenantAwareEntity.TENANT_PARAM, type = UUID.class))
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "organization_id = :organizationId")
public abstract class TenantAwareEntity extends BaseEntity {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String TENANT_PARAM = "organizationId";

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;
}
