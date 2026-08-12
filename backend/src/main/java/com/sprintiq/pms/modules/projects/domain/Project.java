package com.sprintiq.pms.modules.projects.domain;

import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Delivery container inside a workspace. Owns the agile hierarchy (epics, stories,
 * tasks), its own sprint cadence and its own custom workflow.
 *
 * <p>{@code itemSequence} is the per-project counter behind human-readable keys
 * ({@code MBA-142}). It is bumped under a row lock by {@code ItemKeyService} rather
 * than by a database sequence, because keys must be gapless and per-project — a shared
 * sequence would leak the platform's global item count into every tenant's keys.</p>
 */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** Uppercase prefix of every item key in this project. Immutable once issued. */
    @Column(name = "project_key", nullable = false, length = 10, updatable = false)
    private String projectKey;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ProjectStatus status = ProjectStatus.PLANNING;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "health", nullable = false, length = 24)
    private ProjectHealth health = ProjectHealth.ON_TRACK;

    @Builder.Default
    @Column(name = "progress_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal progressPct = BigDecimal.ZERO;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "target_date")
    private LocalDate targetDate;

    /** Planning budget only — SprintIQ never bills, invoices or moves money (A8). */
    @Column(name = "budget_amount", precision = 14, scale = 2)
    private BigDecimal budgetAmount;

    /** ISO 4217 code; CHAR(3) in SQL, so the JDBC type is pinned — see {@code Organization}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "lead_user_id")
    private UUID leadUserId;

    @Column(name = "team_id")
    private UUID teamId;

    /** Feature for the M1 story-point model: estimates vary by problem domain. */
    @Column(name = "domain", length = 80)
    private String domain;

    /** Feature for the M1 story-point model: the stack the team is working in. */
    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "tech_stack", nullable = false, columnDefinition = "jsonb")
    private List<String> techStack = new ArrayList<>();

    @Builder.Default
    @Column(name = "default_sprint_days", nullable = false)
    private short defaultSprintDays = 14;

    /**
     * What the M1 model needs to know about the team rather than the story.
     *
     * <p>All nullable, and deliberately so. A project without them still estimates —
     * the model service falls back to the medians of its training set and says in the
     * explanation that it did. Defaulting them here instead would be worse: storing
     * zero velocity claims the team has never delivered anything, and the model would
     * price every story accordingly.</p>
     */
    @Column(name = "team_size")
    private Short teamSize;

    /** junior, mid or senior — the predominant level, not a per-person record. */
    @Column(name = "dev_experience_level", length = 16)
    private String devExperienceLevel;

    /** Average points completed per sprint. Seeded at creation, measured later. */
    @Column(name = "team_velocity_avg", precision = 6, scale = 2)
    private BigDecimal teamVelocityAvg;

    /** Proportion of committed work finished recently, 0..1 — not a percentage. */
    @Column(name = "recent_completion_rate", precision = 4, scale = 3)
    private BigDecimal recentCompletionRate;

    /**
     * The one tech stack the model is given, out of the several a project may list.
     *
     * <p>The model was trained on a single stack per row, so it has to be told one.
     * The first entry is the team's own ordering — the primary stack is what people
     * put first — which is a better answer than picking alphabetically.</p>
     */
    public String primaryTechStack() {
        return techStack == null || techStack.isEmpty() ? null : techStack.get(0);
    }

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "definition_of_ready", nullable = false, columnDefinition = "jsonb")
    private List<String> definitionOfReady = new ArrayList<>();

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "definition_of_done", nullable = false, columnDefinition = "jsonb")
    private List<String> definitionOfDone = new ArrayList<>();

    @Builder.Default
    @Column(name = "item_sequence", nullable = false)
    private int itemSequence = 0;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Allocates the next key ({@code MBA-143}). Callers must hold a write lock on the row. */
    public String nextItemKey() {
        itemSequence += 1;
        return "%s-%d".formatted(projectKey, itemSequence);
    }
}
