package com.sprintiq.pms.modules.settings.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A capability switch, resolved workspace-first and falling back to the organization.
 *
 * <p>Two levels rather than one because the two decisions are different. An
 * organization deciding it does not want cost forecasting is a policy; one workspace
 * turning off story point suggestions while it builds trust in them is an experiment.
 * Collapsing them would force the experiment to be a policy.</p>
 *
 * <p>A missing row means enabled. The alternative — provisioning a row per flag per
 * workspace at creation time — means every new capability needs a backfill migration
 * before anyone can use it.</p>
 */
@Entity
@Table(name = "feature_flags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeatureFlag extends TenantAwareEntity {

    /** Null means the organization-wide default. */
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "flag_key", nullable = false, length = 80, updatable = false)
    private String flagKey;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Per-flag tuning — thresholds, sample rates — without a schema change per knob. */
    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config = new HashMap<>();
}
