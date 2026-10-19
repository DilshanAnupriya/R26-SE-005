package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.domain.RiskAssessment;
import com.sprintiq.pms.modules.ai.domain.RiskAssessmentRepository;
import com.sprintiq.pms.modules.ai.domain.RiskSeverity;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.DailyRiskPoint;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.SprintRiskComparison;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How a sprint's risk moved, and how two sprints compare.
 *
 * <p>Both read the assessment history rather than re-scoring. Re-scoring a past day is
 * not possible honestly — the features would be today's — and a chart that quietly
 * redrew last Tuesday every time it was opened would be worse than no chart.</p>
 */
@Service
@RequiredArgsConstructor
public class SprintRiskTrendService {

    private final RiskAssessmentRepository assessments;
    private final SprintFeatureSource sprints;
    private final SprintRiskAdjustmentService adjustmentService;

    /**
     * The hour a day is read at.
     *
     * <p>A day needs one number, and "the last scoring of the day" would make the chart
     * depend on what time people happened to press the button. Late afternoon is the
     * defensible choice: it is after the day's work has happened and before anyone has
     * gone home to fix it.</p>
     */
    private static final int SNAPSHOT_HOUR_UTC = 17;

    /**
     * How the three models combine into one line.
     *
     * <p>Over-budget carries the most weight because it is the one scored from hard
     * numbers rather than from text and timestamps. The weights are renormalised over
     * whichever models actually answered, so a sprint with no collaboration signal is
     * not silently scored as if its collaboration were perfect.</p>
     */
    private static final Map<RiskType, BigDecimal> WEIGHTS = new EnumMap<>(Map.of(
            RiskType.OVER_BUDGET, BigDecimal.valueOf(0.4),
            RiskType.REQUIREMENT_CHANGE, BigDecimal.valueOf(0.3),
            RiskType.COMMUNICATION_COLLABORATION, BigDecimal.valueOf(0.3)));

    // ------------------------------------------------------------------- trend

    /**
     * One point per calendar day of the sprint.
     *
     * <p>Forward-filled: a day with no scoring of its own carries the last one before
     * it, because the risk did not become unknown overnight — nobody looked. Days before
     * the first scoring, and days still in the future, are marked {@code hasData=false}
     * and drawn as gaps. Rendering them as zero would put "no risk" on the chart for
     * every day of a sprint nobody has assessed yet, which is the single most misleading
     * thing this endpoint could say.</p>
     *
     * <p>Today's point is read at the current instant rather than at the snapshot hour,
     * so a scoring run five minutes ago shows immediately. Every point carries the
     * adjustment overlay as it stood at its own reading, so the last point agrees with
     * the gauges beside it and earlier points agree with what the gauges said then.</p>
     */
    @Transactional(readOnly = true)
    public List<DailyRiskPoint> dailyTrend(UUID sprintId) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        LocalDate start = sprint.startDate();
        LocalDate end = sprint.endDate();
        if (start == null || end == null || end.isBefore(start)) {
            return List.of();
        }

        Map<RiskType, List<RiskAssessment>> history = new EnumMap<>(RiskType.class);
        for (RiskType type : RiskType.values()) {
            history.put(type, assessments.findBySprintIdAndRiskTypeOrderByCreatedAtAsc(sprintId, type));
        }

        SprintRiskAdjustmentService.Ledger ledger = adjustmentService.ledgerFor(sprintId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant now = Instant.now();

        List<DailyRiskPoint> points = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean isToday = day.equals(today);
            if (day.isAfter(today)) {
                points.add(gap(day, false));
                continue;
            }
            Instant cutoff = isToday ? now : day.atTime(SNAPSHOT_HOUR_UTC, 0).toInstant(ZoneOffset.UTC);

            Map<RiskType, BigDecimal> scores = new EnumMap<>(RiskType.class);
            for (RiskType type : RiskType.values()) {
                RiskAssessment latest = lastAtOrBefore(history.get(type), cutoff);
                if (latest == null) {
                    continue;
                }
                // Every day carries the overlay as it stood at that day's reading. Past
                // days count only what the board had recorded by their cutoff, so this
                // afternoon's blocker is never backdated onto last Tuesday — but last
                // Tuesday's blocker still shows on last Tuesday, as it did on the gauges.
                BigDecimal delta = isToday
                        ? ledger.netSince(type, latest.getCreatedAt())
                        : ledger.netBetween(type, latest.getCreatedAt(), cutoff);
                scores.put(type, applyAdjustment(latest.getRiskScore(), delta));
            }

            if (scores.isEmpty()) {
                points.add(gap(day, isToday));
                continue;
            }

            BigDecimal overall = blend(scores);
            points.add(new DailyRiskPoint(day.toString(), overall, RiskSeverity.forScore(overall),
                    scores.get(RiskType.OVER_BUDGET), scores.get(RiskType.REQUIREMENT_CHANGE),
                    scores.get(RiskType.COMMUNICATION_COLLABORATION), true, isToday));
        }
        return points;
    }

    // ----------------------------------------------------------------- compare

    /**
     * Latest risk for several sprints, side by side.
     *
     * <p>Sprints that have never been scored are returned with null scores rather than
     * dropped. A comparison that silently omits a column is a comparison that answers a
     * different question from the one asked, and "we never assessed that one" is itself
     * the finding worth seeing.</p>
     */
    @Transactional(readOnly = true)
    public List<SprintRiskComparison> compare(List<UUID> sprintIds) {
        List<UUID> distinct = sprintIds == null ? List.of() : sprintIds.stream().distinct().toList();
        if (distinct.size() < 2) {
            throw new BusinessRuleException("Comparing needs at least two different sprints");
        }
        // Capped because the comparison is rendered as columns, and six of them is a
        // table nobody reads rather than a comparison anybody makes.
        if (distinct.size() > 5) {
            throw new BusinessRuleException("At most five sprints can be compared at once");
        }

        List<SprintRiskComparison> out = new ArrayList<>(distinct.size());
        for (UUID sprintId : distinct) {
            SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
            SprintRiskAdjustmentService.Ledger ledger = adjustmentService.ledgerFor(sprintId);

            Map<RiskType, BigDecimal> scores = new EnumMap<>(RiskType.class);
            Instant assessedAt = null;
            BigDecimal totalDelta = BigDecimal.ZERO;

            for (RiskType type : RiskType.values()) {
                RiskAssessment latest = assessments
                        .findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(sprintId, type)
                        .orElse(null);
                if (latest == null) {
                    continue;
                }
                BigDecimal delta = ledger.netSince(type, latest.getCreatedAt());
                totalDelta = totalDelta.add(delta);
                scores.put(type, applyAdjustment(latest.getRiskScore(), delta));
                if (assessedAt == null || latest.getCreatedAt().isAfter(assessedAt)) {
                    assessedAt = latest.getCreatedAt();
                }
            }

            BigDecimal overall = scores.isEmpty() ? null : blend(scores);
            out.add(new SprintRiskComparison(sprintId, sprint.name(), sprint.status(),
                    sprint.startDate(), sprint.endDate(),
                    overall, overall == null ? null : RiskSeverity.forScore(overall),
                    scores.get(RiskType.OVER_BUDGET), scores.get(RiskType.REQUIREMENT_CHANGE),
                    scores.get(RiskType.COMMUNICATION_COLLABORATION),
                    sprint.committedPoints(), sprint.completedPoints(),
                    sprint.blockedCount(), sprint.reopenedCount(),
                    totalDelta.setScale(2, RoundingMode.HALF_UP), assessedAt));
        }
        return out;
    }

    // --------------------------------------------------------------- internals

    private static DailyRiskPoint gap(LocalDate day, boolean current) {
        return new DailyRiskPoint(day.toString(), null, null, null, null, null, false, current);
    }

    /** The last scoring at or before {@code cutoff}. The list is ascending, so this walks forward. */
    private static RiskAssessment lastAtOrBefore(List<RiskAssessment> history, Instant cutoff) {
        RiskAssessment found = null;
        for (RiskAssessment assessment : history) {
            if (assessment.getCreatedAt().isAfter(cutoff)) {
                break;
            }
            found = assessment;
        }
        return found;
    }

    /** Ledger points are on a 0–100 scale; scores are 0–1 probabilities. */
    private static BigDecimal applyAdjustment(BigDecimal score, BigDecimal deltaPoints) {
        if (score == null) {
            return null;
        }
        if (deltaPoints == null || deltaPoints.signum() == 0) {
            return score;
        }
        return score.add(deltaPoints.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
                .max(BigDecimal.ZERO).min(BigDecimal.ONE)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** Weighted mean over the models that answered, renormalised so the result stays 0–1. */
    private static BigDecimal blend(Map<RiskType, BigDecimal> scores) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (Map.Entry<RiskType, BigDecimal> entry : scores.entrySet()) {
            BigDecimal weight = WEIGHTS.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            weighted = weighted.add(entry.getValue().multiply(weight));
            totalWeight = totalWeight.add(weight);
        }
        if (totalWeight.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return weighted.divide(totalWeight, 4, RoundingMode.HALF_UP);
    }
}
