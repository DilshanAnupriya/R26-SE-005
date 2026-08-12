package com.sprintiq.pms.modules.settings.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.Type;

import java.util.UUID;

/**
 * One key/value pair of workspace configuration, stored as JSON.
 *
 * <p>Key-value rather than a wide settings table because settings arrive one at a time
 * and forever: working days, default sprint length, estimation scale, the next dozen
 * nobody has thought of yet. A column per setting means a migration per preference and a
 * table that only ever grows sideways.</p>
 *
 * <p>The value is JSONB rather than text so a setting can be a list or an object —
 * "working days" is naturally an array, and encoding it as a comma-separated string
 * would put a parser in the read path of every caller.</p>
 */
@Entity
@Table(name = "workspace_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceSetting extends TenantAwareEntity {

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "setting_key", nullable = false, length = 80, updatable = false)
    private String settingKey;

    /**
     * Typed as a {@link JsonNode} rather than {@code Object}.
     *
     * <p>A plain {@code Object} field lets Hibernate fall back to {@code toString()},
     * which writes {@code TSHIRT} into a {@code jsonb} column instead of
     * {@code "TSHIRT"} — Postgres rejects it, and only for the settings that happen to
     * be scalars. A node is always valid JSON whether the value is a string, a number,
     * a boolean or a list.</p>
     */
    @Type(JsonType.class)
    @Column(name = "setting_value", nullable = false, columnDefinition = "jsonb")
    private JsonNode settingValue;
}
