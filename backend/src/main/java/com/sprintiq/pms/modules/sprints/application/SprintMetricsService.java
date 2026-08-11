package com.sprintiq.pms.modules.sprints.application;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.backlog.application.SprintBacklogService;
import com.sprintiq.pms.modules.sprints.domain.*;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Daily sprint snapshots, burndown and the metrics roll-up.
 *
 * <p>Snapshots are taken on demand — on sprint start, on completion, and whenever the
 * burndown is read — rather than only by a nightly job. A team that opens the chart at
 * 11am should see today's line, and a deployment that missed a night should not leave a
 * hole in the history.</p>
 */
@Service
@RequiredArgsConstructor
public class SprintMetricsService {

    /** Sprints averaged for the rolling velocity figure shown next to the current one. */
    private static final int VELOCITY_WINDOW = 6;

    private final SprintMetricRepository metricRepository;
    private final SprintRepository sprintRepository;
    private final SprintBacklogService sprintBacklog;

    /**
     * Records (or overwrites) one day's row for a sprint.
     *
     * <p>Upsert rather than insert: reading the burndown twice in a day must not
     * produce two points for the same date, and the later reading is the more accurate
     * one.</p>
     */
    @Transactional
    public SprintMetric snapshot(Sprint sprint, LocalDate date) {
        SprintBacklogService.SprintSnapshot state = sprintBacklog.snapshotOf(sprint.getId());

        SprintMetric metric = metricRepository.findBySprintIdAndSnapshotDate(sprint.getId(), date)
                .orElseGet(() -> {
                    SprintMetric created = SprintMetric.builder()
                            .sprintId(sprint.getId())
                            .snapshotDate(date)
                            .build();
                    created.setOrganizationId(TenantContext.requireOrganizationId());
                    return created;
                });

        BigDecimal committed = sprint.getCommittedPoints().compareTo(BigDecimal.ZERO) > 0
                ? sprint.getCommittedPoints()
                : state.committed();

        metric.setCommittedPoints(committed);
        metric.setCompletedPoints(state.completed());
        metric.setRemainingPoints(state.remaining());
        metric.setScopeAddedPoints(sprint.getAddedPoints());
        metric.setScopeRemovedPoints(sprint.getRemovedPoints());
        metric.setIdealRemaining(idealRemaining(sprint, date, committed));
        metric.setVelocity(state.completed());
        metric.setBlockedCount((short) Math.min(state.blockedCount(), Short.MAX_VALUE));
        metric.setDefectCount((short) Math.min(state.defectCount(), Short.MAX_VALUE));
        metric.setReopenedCount((short) Math.min(state.reopenedCount(), Short.MAX_VALUE));
        metric.setScopeVolatility(scopeVolatility(sprint, committed));
        metric.setActiveContributors((short) Math.min(state.contributorCount(), Short.MAX_VALUE));
        metric.setEffortMinutes(sprint.getTotalEffortMinutes());

        return metricRepository.save(metric);
    }

    /**
     * Burndown series. Reads recorded history and tops it up with today's point, so an
     * in-flight sprint always ends at the present rather than at the last snapshot.
     */
    @Transactional
    public BurndownResponse burndown(UUID sprintId) {
        Sprint sprint = sprintRepository.findByIdAndDeletedAtIsNull(sprintId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint", sprintId));

        if (sprint.getStatus() == SprintStatus.ACTIVE) {
            snapshot(sprint, LocalDate.now());
        }

        List<SprintMetric> history = metricRepository.findBySprintIdOrderBySnapshotDateAsc(sprintId);
        SprintBacklogService.SprintSnapshot state = sprintBacklog.snapshotOf(sprintId);
        BigDecimal committed = sprint.getCommittedPoints().compareTo(BigDecimal.ZERO) > 0
                ? sprint.getCommittedPoints()
                : state.committed();

        List<BurndownPoint> points = new ArrayList<>(history.size());
        for (SprintMetric metric : history) {
            points.add(new BurndownPoint(
                    metric.getSnapshotDate(),
                    metric.getIdealRemaining(),
                    metric.getRemainingPoints(),
                    metric.getCompletedPoints(),
                    metric.getScopeAddedPoints().subtract(metric.getScopeRemovedPoints())));
        }

        // A sprint that has not started yet has no history; show the ideal line alone so
        // the planning view still renders something meaningful.
        if (points.isEmpty() && sprint.getStartDate() != null && sprint.getEndDate() != null) {
            for (LocalDate date = sprint.getStartDate(); !date.isAfter(sprint.getEndDate());
                 date = date.plusDays(1)) {
                points.add(new BurndownPoint(date, idealRemaining(sprint, date, committed), null, null,
                        BigDecimal.ZERO));
            }
        }

        return new BurndownResponse(sprintId, sprint.getStartDate(), sprint.getEndDate(), committed,
                state.completed(), state.remaining(), points);
    }

    @Transactional(readOnly = true)
    public SprintMetricsResponse metrics(UUID sprintId) {
        Sprint sprint = sprintRepository.findByIdAndDeletedAtIsNull(sprintId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint", sprintId));
        SprintBacklogService.SprintSnapshot state = sprintBacklog.snapshotOf(sprintId);

        BigDecimal committed = sprint.getCommittedPoints();
        BigDecimal completed = sprint.getStatus().isTerminal()
                ? sprint.getCompletedPoints()
                : state.completed();

        return new SprintMetricsResponse(
                sprintId,
                committed,
                completed,
                sprint.getAddedPoints(),
                sprint.getRemovedPoints(),
                sprint.completionRate(),
                scopeVolatility(sprint, committed).multiply(BigDecimal.valueOf(100)),
                state.storyCount(),
                state.completedCount(),
                Math.max(0, state.storyCount() - state.completedCount()),
                state.blockedCount(),
                state.defectCount(),
                state.reopenedCount(),
                sprint.getTotalEffortMinutes(),
                completed,
                rollingVelocity(sprint.getProjectId()));
    }

    /**
     * Mean velocity over the last completed sprints — the planning baseline, and the
     * historical feature the M1 velocity model is anchored against.
     */
    @Transactional(readOnly = true)
    public BigDecimal rollingVelocity(UUID projectId) {
        List<Sprint> history = sprintRepository.findCompletedHistory(projectId,
                PageRequest.of(0, VELOCITY_WINDOW));
        if (history.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = history.stream()
                .map(Sprint::getCompletedPoints)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(history.size()), 2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Where a linear burn would sit on {@code date}. Uses calendar days rather than
     * working days: a two-week sprint's ideal line is a guide, and weekend-adjusting it
     * implies a precision the estimate underneath it does not have.
     */
    private BigDecimal idealRemaining(Sprint sprint, LocalDate date, BigDecimal committed) {
        if (sprint.getStartDate() == null || sprint.getEndDate() == null
                || committed.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        long totalDays = ChronoUnit.DAYS.between(sprint.getStartDate(), sprint.getEndDate());
        if (totalDays <= 0) {
            return BigDecimal.ZERO;
        }
        long elapsed = ChronoUnit.DAYS.between(sprint.getStartDate(), date);
        if (elapsed <= 0) return committed;
        if (elapsed >= totalDays) return BigDecimal.ZERO;

        BigDecimal burnedFraction = BigDecimal.valueOf(elapsed)
                .divide(BigDecimal.valueOf(totalDays), 6, RoundingMode.HALF_UP);
        return committed.subtract(committed.multiply(burnedFraction)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Churn as a fraction of the original commitment. Zero commitment means zero churn. */
    private BigDecimal scopeVolatility(Sprint sprint, BigDecimal committed) {
        if (committed == null || committed.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal churn = sprint.getAddedPoints().add(sprint.getRemovedPoints());
        return churn.divide(committed, 4, RoundingMode.HALF_UP);
    }
}
