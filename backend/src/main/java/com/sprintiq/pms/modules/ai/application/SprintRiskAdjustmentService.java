package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustment;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustmentRepository;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ledger that keeps a sprint's risk figure honest between model runs.
 *
 * <p>Published for the delivery modules to write to. The dependency runs that way round
 * on purpose: the AI module must not reach into tasks or projects (see the layering
 * rules), so the module that observes the event is the one that reports it, and this
 * service only ever sees "sprint X, this much, because of this".</p>
 *
 * <p>Every write tolerates a null sprint id and does nothing. A task can perfectly well
 * be blocked while sitting in the backlog, and making each caller guard for that would
 * put the same null check in six places, where one of them would eventually be missed
 * and throw on a board drag.</p>
 */
@Service
@RequiredArgsConstructor
public class SprintRiskAdjustmentService {

    private final SprintRiskAdjustmentRepository adjustments;

    /** Adjustments approach, but never reach, this many points in either direction. */
    private static final BigDecimal LIMIT = BigDecimal.valueOf(30);

    /** Below this magnitude the accumulated total is passed through untouched. */
    private static final BigDecimal KNEE = BigDecimal.valueOf(20);

    // ------------------------------------------------------------------ writes

    /**
     * Records an event. Events accumulate — two tasks blocked is twice the pressure of
     * one, and that is the whole point of recording them separately.
     */
    @Transactional
    public void record(UUID sprintId, RiskType riskType, BigDecimal delta, String reason) {
        if (sprintId == null || delta == null || delta.signum() == 0) {
            return;
        }
        adjustments.save(build(sprintId, riskType, delta, reason, null));
    }

    /**
     * Records a state, revising the existing reading rather than adding to it.
     *
     * <p>A task reopened six times is one task with a worse reading. Appending would
     * claim six tasks broke down, which is both wrong and unbounded — the ledger would
     * grow with clicks rather than with evidence.</p>
     *
     * <p>A changed reading is replaced rather than updated, so the row carries today's
     * {@code createdAt}. That date is not decoration: it is the cutoff
     * {@link Ledger#netSince} measures against, and a row still holding its original
     * timestamp reads as evidence the last scoring already absorbed. It silently
     * inverted the ledger after any re-assessment — the next reopen of a task whose row
     * predated it did not count, while the credit for finishing that task again did, so
     * <em>more</em> rework moved the score down. Replacing says what is true: as of now
     * this task has been reopened this many times, and no scoring has seen it.</p>
     *
     * <p>Delete-and-insert rather than {@code setCreatedAt}, because {@code created_at}
     * is mapped {@code updatable = false} — the assignment is real in the entity and
     * silently dropped from the UPDATE, which is the kind of fix that looks applied and
     * changes nothing.</p>
     *
     * <p>Only when the value actually moves. Re-dating an unchanged reading would let a
     * no-op call resurrect an adjustment the last scoring had properly absorbed.</p>
     */
    @Transactional
    public void revise(UUID sprintId, RiskType riskType, String sourceKey,
                       BigDecimal delta, String reason) {
        if (sprintId == null || sourceKey == null) {
            return;
        }
        SprintRiskAdjustment existing =
                adjustments.findBySprintIdAndSourceKey(sprintId, sourceKey).orElse(null);
        if (existing != null && existing.getDelta() != null
                && existing.getDelta().compareTo(delta) == 0) {
            existing.setReason(reason);
            adjustments.save(existing);
            return;
        }
        if (existing != null) {
            adjustments.delete(existing);
            // (sprint_id, source_key) is unique, so the insert below has to land after
            // the delete reaches the database rather than alongside it at flush time.
            adjustments.flush();
        }
        adjustments.save(build(sprintId, riskType, delta, reason, sourceKey));
    }

    /**
     * Records a state exactly once, ignoring repeats.
     *
     * <p>Used for the overdue penalty, which several open browser tabs will all notice
     * within the same second. Without this the sprint would be charged once per viewer,
     * which would make the risk score a function of how many people were looking at
     * it.</p>
     */
    @Transactional
    public void recordOnce(UUID sprintId, RiskType riskType, String sourceKey,
                           BigDecimal delta, String reason) {
        if (sprintId == null || sourceKey == null
                || adjustments.existsBySprintIdAndSourceKey(sprintId, sourceKey)) {
            return;
        }
        adjustments.save(build(sprintId, riskType, delta, reason, sourceKey));
    }

    /** Withdraws a state adjustment — the condition it described no longer holds. */
    @Transactional
    public void clear(UUID sprintId, String sourceKey) {
        if (sprintId == null || sourceKey == null) {
            return;
        }
        adjustments.deleteBySprintIdAndSourceKey(sprintId, sourceKey);
    }

    // ------------------------------------------------------------------- reads

    /**
     * The whole ledger for one sprint, loaded once.
     *
     * <p>Returned as an object rather than a total because the caller needs several
     * different totals out of it — one per risk type, each measured from that type's own
     * last scoring — and re-querying per type would be three round trips for data that
     * arrives in one.</p>
     */
    @Transactional(readOnly = true)
    public Ledger ledgerFor(UUID sprintId) {
        return new Ledger(sprintId == null
                ? List.of() : adjustments.findBySprintIdOrderByCreatedAtDesc(sprintId));
    }

    /** Net points per risk type across the whole ledger, soft-capped. */
    @Transactional(readOnly = true)
    public Map<RiskType, BigDecimal> netFor(UUID sprintId) {
        return ledgerFor(sprintId).net();
    }

    /**
     * Board activity behind an adjusted score, newest first.
     *
     * <p>Only what a model has not already seen. Everything older than a type's last
     * scoring is baked into the features that scoring read, and showing it again would
     * present the same blocked task as both a driver and a subsequent adjustment.</p>
     */
    public record Ledger(List<SprintRiskAdjustment> entries) {

        /**
         * Net points recorded for one risk type <em>after</em> the given instant.
         *
         * <p>The cutoff is what stops the overlay double-counting. A blocked task
         * recorded before the last scoring is already in {@code blocked_count}, which
         * the model read; only what has happened since is genuinely news. It also gives
         * "Assess" an honest meaning: re-scoring folds the overlay into the model's own
         * figure and the adjustment starts again from zero.</p>
         *
         * @param since the scoring the adjustment is measured against, or null to
         *              take the whole ledger
         */
        public BigDecimal netSince(RiskType riskType, java.time.Instant since) {
            BigDecimal total = BigDecimal.ZERO;
            for (SprintRiskAdjustment entry : entries) {
                if (entry.getRiskType() != riskType) {
                    continue;
                }
                if (since != null && !entry.getCreatedAt().isAfter(since)) {
                    continue;
                }
                total = total.add(entry.getDelta());
            }
            return softCap(total);
        }

        /** Every type's net, over the whole ledger. Always carries all three keys. */
        public Map<RiskType, BigDecimal> net() {
            Map<RiskType, BigDecimal> totals = new EnumMap<>(RiskType.class);
            for (RiskType type : RiskType.values()) {
                totals.put(type, netSince(type, null));
            }
            return totals;
        }

        /** Entries logged after the given instant, for the "what has changed since" panel. */
        public List<SprintRiskAdjustment> since(java.time.Instant cutoff) {
            return cutoff == null
                    ? entries
                    : entries.stream().filter(entry -> entry.getCreatedAt().isAfter(cutoff)).toList();
        }
    }

    // --------------------------------------------------------------- internals

    private SprintRiskAdjustment build(UUID sprintId, RiskType riskType, BigDecimal delta,
                                       String reason, String sourceKey) {
        SprintRiskAdjustment adjustment = SprintRiskAdjustment.builder()
                .sprintId(sprintId)
                .riskType(riskType)
                .delta(delta)
                .reason(truncate(reason))
                .sourceKey(sourceKey)
                .build();
        adjustment.setOrganizationId(TenantContext.requireOrganizationId());
        return adjustment;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "Board activity";
        }
        return reason.length() <= 200 ? reason : reason.substring(0, 197) + "...";
    }

    /**
     * Bounds accumulated pressure without letting it flatline early.
     *
     * <p>A hard clamp was the obvious first answer and the wrong one: once a sprint had
     * banked its ceiling the total stopped moving, so further evidence — another reopen,
     * another blocker — changed the score by exactly nothing while the UI went on saying
     * it would raise the risk. A sprint with five reopened tasks reaches a 30-point
     * ceiling on reopens alone, which is precisely when the next reopen most deserves to
     * register.</p>
     *
     * <p>Below the knee the value is untouched, so ordinary sprints behave as if this
     * function were not here. Past it each further point still moves the total, by a
     * progressively smaller amount, approaching the limit asymptotically.</p>
     *
     * <p>Two things worth being precise about, because the tempting summary
     * ("strictly increasing, never reaches the limit") is only true in exact
     * arithmetic. The result is rounded to four decimals, so the increments become
     * invisible somewhere past a hundred raw points and the value does eventually
     * settle at the limit exactly. That is far enough out to be beyond any sprint this
     * will see — the point of the curve is that it keeps responding through the range
     * a real sprint occupies, not that it responds for ever.</p>
     */
    static BigDecimal softCap(BigDecimal value) {
        BigDecimal magnitude = value.abs();
        if (magnitude.compareTo(KNEE) <= 0) {
            return value.setScale(SCALE, RoundingMode.HALF_UP);
        }
        double span = LIMIT.subtract(KNEE).doubleValue();
        double excess = magnitude.subtract(KNEE).doubleValue();
        double saturated = KNEE.doubleValue() + span * (1.0 - Math.exp(-excess / span));
        BigDecimal result = BigDecimal.valueOf(saturated).setScale(SCALE, RoundingMode.HALF_UP);
        return value.signum() < 0 ? result.negate() : result;
    }

    /**
     * Decimals kept in the capped total.
     *
     * <p>Four rather than the two the ledger's own column stores. The cap is applied to
     * a sum on the read path, never written back, and rounding it to the storage
     * precision is what made the curve stop responding at around seventy points — well
     * inside the range a busy sprint reaches.</p>
     */
    private static final int SCALE = 4;
}
