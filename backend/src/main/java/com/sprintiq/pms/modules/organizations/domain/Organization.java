package com.sprintiq.pms.modules.organizations.domain;

import com.sprintiq.pms.common.domain.BaseEntity;
import com.sprintiq.pms.common.domain.SoftDeletable;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Tenant root. Every other tenant-scoped row points back here via organization_id. */
@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization extends BaseEntity implements SoftDeletable {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 24)
    private OrganizationPlan plan = OrganizationPlan.FREE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Builder.Default
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "UTC";

    @Builder.Default
    @Column(name = "working_days", nullable = false)
    private short workingDays = 5;

    @Builder.Default
    @Column(name = "default_hourly_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal defaultHourlyRate = BigDecimal.ZERO;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
