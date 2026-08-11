package com.sprintiq.pms.common.domain;

import com.sprintiq.pms.tenancy.TenantEntityListener;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Root for tenant-scoped rows that are written once and never edited — AI predictions,
 * risk assessments, allocation suggestions and cost forecasts.
 *
 * <p>These tables deliberately have no {@code updated_at}, no {@code version} and no
 * soft delete. A prediction is a statement about what a model said at a moment in time;
 * editing it destroys the only thing it is good for, which is comparing what was
 * predicted against what actually happened. Making them extend {@link BaseEntity} would
 * hand every one of them an update path that must then be defended in review forever.</p>
 *
 * <p>They still carry the tenant filter. {@code ActivityLog} — the older append-only
 * table — scopes itself by passing {@code organizationId} into each query, which works
 * but relies on every future query author remembering. Inheriting the filter here means
 * a forgotten predicate narrows to nothing rather than widening across tenants.</p>
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners({TenantEntityListener.class, AuditingEntityListener.class})
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "organization_id = :organizationId")
public abstract class AppendOnlyTenantEntity implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null) return false;
        if (!org.hibernate.Hibernate.getClass(this).equals(org.hibernate.Hibernate.getClass(other))) {
            return false;
        }
        AppendOnlyTenantEntity that = (AppendOnlyTenantEntity) other;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return org.hibernate.Hibernate.getClass(this).hashCode();
    }
}
