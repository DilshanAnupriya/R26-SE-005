package com.sprintiq.pms.modules.tasks.domain;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A block of logged effort.
 *
 * <p>Serves both entry styles: a manual "I spent 90 minutes on this" and a running
 * timer. A timer in flight is a row with {@code startedAt} set and {@code stoppedAt}
 * still null — persisting it rather than holding it in memory means a timer survives a
 * refresh, a redeploy and a laptop closing, which is the whole reason people distrust
 * timers in other tools.</p>
 *
 * <p>{@code isOvertime} is kept because the M4 cost model prices overtime differently
 * from ordinary hours.</p>
 */
@Entity
@Table(name = "time_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimeEntry extends TenantAwareEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "sprint_id")
    private UUID sprintId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20, updatable = false)
    private EntityType entityType = EntityType.TASK;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Must stay positive — a zero-minute entry is a timer that was never really running. */
    @Builder.Default
    @Column(name = "minutes", nullable = false)
    private int minutes = 0;

    @Builder.Default
    @Column(name = "is_overtime", nullable = false)
    private boolean overtime = false;

    @Column(name = "description", length = 500)
    private String description;

    @Builder.Default
    @Column(name = "logged_on", nullable = false)
    private LocalDate loggedOn = LocalDate.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    /** A timer that has been started and not yet stopped. */
    public boolean isRunning() {
        return startedAt != null && stoppedAt == null;
    }

    /**
     * Stops a running timer and converts the elapsed wall time into minutes.
     *
     * <p>Rounds up to one minute: someone who started a timer did some work, and a
     * fifty-second entry recorded as zero looks like the timer was broken.</p>
     */
    public void stop(Instant when) {
        stoppedAt = when;
        long elapsed = Duration.between(startedAt, when).toMinutes();
        minutes = (int) Math.max(1, elapsed);
        loggedOn = when.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }
}
