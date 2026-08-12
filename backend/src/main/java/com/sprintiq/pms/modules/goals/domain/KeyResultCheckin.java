package com.sprintiq.pms.modules.goals.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One recorded measurement of a key result.
 *
 * <p>Append-only history, kept separate from {@code current_value} because "where is
 * this now" and "how did it get here" are different questions. Without the trail, a
 * goal that slipped badly in week three and recovered looks identical to one that
 * moved steadily — and only one of those tells you something about the team.</p>
 *
 * <p>Extends {@link AppendOnlyTenantEntity}: no updated_at, no version and no soft
 * delete, because editing a check-in destroys the only thing it is good for. It also
 * inherits the tenant filter from that root, so a query that forgets to scope itself
 * narrows to nothing rather than widening across tenants.</p>
 */
@Entity
@Table(name = "key_result_checkins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class KeyResultCheckin extends AppendOnlyTenantEntity {

    @Column(name = "key_result_id", nullable = false, updatable = false)
    private UUID keyResultId;

    @Column(name = "value", nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    /** What it was before, so a reader sees the delta without fetching the neighbour. */
    @Column(name = "previous_value", precision = 14, scale = 2)
    private BigDecimal previousValue;

    /** 1–5, how sure the owner is of hitting the target. Optional. */
    @Column(name = "confidence")
    private Short confidence;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    /**
     * When the measurement was taken, which is not always when it was typed in —
     * someone catching up on Monday is recording Friday's number.
     */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @PrePersist
    void defaultRecordedAt() {
        if (recordedAt == null) recordedAt = Instant.now();
    }
}
