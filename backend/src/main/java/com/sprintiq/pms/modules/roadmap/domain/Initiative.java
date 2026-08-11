package com.sprintiq.pms.modules.roadmap.domain;

import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A body of work large enough to name on a roadmap.
 *
 * <p>Not a goal. A goal is an outcome you are trying to cause; an initiative is the
 * work you intend to do. The distinction matters because they fail differently — an
 * initiative can ship on time and still not move the number it was meant to move,
 * and a roadmap that hides that has told you the wrong thing.</p>
 *
 * <p>{@code progressPct} is a cached roll-up of the linked epics, never typed in.</p>
 */
@Entity
@Table(name = "initiatives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Initiative extends TenantAwareEntity implements SoftDeletable {

    /** Null for an initiative that spans projects, which is the interesting case. */
    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private InitiativeStatus status = InitiativeStatus.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Confidence confidence = Confidence.MEDIUM;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "progress_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal progressPct = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private short position = 0;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * The quarter this initiative lands in — the roadmap column it is drawn in.
     *
     * <p>Derived from the target date rather than stored. An initiative that slips
     * should move on the board by itself; a stored quarter label waits for somebody
     * to remember to retype it, and until they do the board is quietly lying.</p>
     */
    public String quarter() {
        return targetDate == null ? null : quarterOf(targetDate);
    }

    public static String quarterOf(LocalDate date) {
        return "%d-Q%d".formatted(date.getYear(), (date.getMonthValue() - 1) / 3 + 1);
    }

    /**
     * Recomputes progress and status from the work linked to this initiative.
     *
     * <p>Status derivation follows the same rule as goals: measure against the
     * calendar, not against progress alone. Every initiative is at 0% the day it is
     * created, and calling that AT_RISK paints the whole roadmap red in week one.</p>
     *
     * <p>A closed initiative keeps its verdict. SHIPPED is a decision somebody made,
     * and letting a late linked story reopen it would rewrite history.</p>
     */
    public void recalculate(BigDecimal derivedProgress, LocalDate today, boolean hasLinkedWork) {
        progressPct = derivedProgress == null
                ? BigDecimal.ZERO
                : derivedProgress.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        if (status.isClosed()) {
            return;
        }
        status = statusOn(today, hasLinkedWork);
    }

    /**
     * What this initiative's status would be today, without changing it.
     *
     * <p>Pure, so the nightly sweep can ask the question without performing a write —
     * and so a test can ask it about a date that is not today.</p>
     *
     * @param hasLinkedWork whether any surviving epic or story is attached. An
     *                      initiative with nothing linked stays PLANNED however late
     *                      it is: there is no work to be behind on, and calling it
     *                      AT_RISK would blame the team for a card somebody drew.
     */
    public InitiativeStatus statusOn(LocalDate today, boolean hasLinkedWork) {
        if (status.isClosed()) {
            return status;
        }
        if (!hasLinkedWork) {
            return InitiativeStatus.PLANNED;
        }
        if (progressPct.compareTo(BigDecimal.valueOf(100)) >= 0) {
            // Everything linked is done. Still not SHIPPED — that is somebody's call,
            // because "all the tickets are closed" and "it is in customers' hands"
            // are different claims and only a person can make the second one.
            return InitiativeStatus.IN_PROGRESS;
        }

        BigDecimal expected = expectedProgressPct(today);
        if (expected == null) {
            // Undated. Nothing to be late against, so the only signal is movement.
            return progressPct.compareTo(BigDecimal.ZERO) == 0
                    ? InitiativeStatus.PLANNED
                    : InitiativeStatus.IN_PROGRESS;
        }

        BigDecimal shortfall = expected.subtract(progressPct);
        if (shortfall.compareTo(BigDecimal.valueOf(30)) > 0) {
            return InitiativeStatus.AT_RISK;
        }
        return progressPct.compareTo(BigDecimal.ZERO) == 0 && expected.compareTo(BigDecimal.ZERO) == 0
                ? InitiativeStatus.PLANNED
                : InitiativeStatus.IN_PROGRESS;
    }

    /**
     * Where a steady effort would have reached by {@code today}, 0–100, or null when
     * the initiative has no dates to pace against.
     */
    public BigDecimal expectedProgressPct(LocalDate today) {
        if (startDate == null || targetDate == null) {
            return null;
        }
        long totalDays = ChronoUnit.DAYS.between(startDate, targetDate);
        if (totalDays <= 0) {
            return BigDecimal.valueOf(100);
        }
        long elapsed = ChronoUnit.DAYS.between(startDate, today);
        if (elapsed <= 0) return BigDecimal.ZERO;
        if (elapsed >= totalDays) return BigDecimal.valueOf(100);

        return BigDecimal.valueOf(elapsed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);
    }

    /** Past its target date without having shipped. */
    public boolean isOverdue(LocalDate today) {
        return targetDate != null && targetDate.isBefore(today) && !status.isClosed();
    }
}
