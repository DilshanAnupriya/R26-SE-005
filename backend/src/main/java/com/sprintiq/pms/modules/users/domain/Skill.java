package com.sprintiq.pms.modules.users.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

/**
 * A named capability inside one organization — "React", "Postgres", "Accessibility".
 *
 * <p>Tenant-scoped rather than a global taxonomy. One company's "Platform" is another's
 * "Infrastructure", and a shared vocabulary would either be too coarse to match work
 * against or force every organization into someone else's ontology.</p>
 */
@Entity
@Table(name = "skills")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Skill extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "category", length = 60)
    private String category;
}
