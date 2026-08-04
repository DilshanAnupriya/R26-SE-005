package com.sprintiq.pms.modules.backlog.domain;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A body of work spanning several sprints, grouping stories under one outcome. */
@Entity
@Table(name = "epics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Epic extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "milestone_id")
    private UUID milestoneId;

    @Column(name = "item_key", nullable = false, length = 24, updatable = false)
    private String itemKey;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private EpicStatus status = EpicStatus.OPEN;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private Priority priority = Priority.MEDIUM;

    @Column(name = "color", length = 16)
    private String color;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "target_date")
    private LocalDate targetDate;

    /**
     * Lexicographic ordering key. Backtick-quoted because {@code rank} is a reserved
     * word in several dialects; Hibernate translates the backticks to whatever the
     * active dialect quotes with.
     */
    @Builder.Default
    @Column(name = "`rank`", nullable = false, length = 64)
    private String rank = "n";

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
