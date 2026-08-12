package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.ai.application.port.DeveloperFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.EffortFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Model 4 — sprint cost forecasting, before and during execution.
 *
 * <p>Planning instrument, not accounting. It answers "can we afford this sprint as
 * planned" while the plan can still change, and it says so in the response: every
 * forecast carries bounds and a confidence, and the API never returns a bare figure
 * that a spreadsheet could mistake for an actual.</p>
 */
@Service
@RequiredArgsConstructor
public class CostForecastService {

    /** Below this much elapsed sprint, logged effort is too thin to extrapolate from. */
    private static final BigDecimal MIN_ELAPSED_FOR_MID_SPRINT = BigDecimal.valueOf(0.10);

    private final AiInferenceService inference;
    private final SprintFeatureSource sprints;
    private final ProjectContextSource projects;
    private final DeveloperFeatureSource developers;
    private final EffortFeatureSource effort;
    private final CostForecastRepository forecasts;

    @Transactional
    public CostForecastResponse forecast(UUID sprintId, CostStage stage) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        ProjectContextSource.ProjectContext project = projects.contextFor(sprint.projectId());

        BigDecimal blendedRate = developers.averageHourlyRate(sprint.projectId());
        long effortMinutes = effort.sprintEffortMinutes(sprintId);
        long overtimeMinutes = effort.sprintOvertimeMinutes(sprintId);
        BigDecimal budget = sprint.budgetAmount() != null ? sprint.budgetAmount() : project.budgetAmount();

        // A mid-sprint forecast asked for on day one has nothing to extrapolate from;
        // answering it with the initial model is more honest than dividing by a rounding
        // error and reporting a confident nonsense figure.
        CostStage effectiveStage = stage == CostStage.MID_SPRINT
                && sprint.elapsedFraction().compareTo(MIN_ELAPSED_FOR_MID_SPRINT) < 0
                ? CostStage.INITIAL
                : stage;

        FeatureVector vector = FeatureVector.create()
                .putNumber("capacity_hours", sprint.capacityHours())
                .putNumber("capacity_points", sprint.capacityPoints())
                .putNumber("team_size", sprint.teamSize())
                .putNumber("duration_days", sprint.durationDays())
                .putNumber("blended_hourly_rate", blendedRate)
                .putNumber("effort_minutes", effortMinutes)
                .putNumber("overtime_hours", BigDecimal.valueOf(overtimeMinutes)
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP))
                .putNumber("absence_days", sprint.absenceDays())
                .putNumber("elapsed_fraction", sprint.elapsedFraction())
                .putNumber("committed_points", sprint.committedPoints())
                .putNumber("completed_points", sprint.completedPoints())
                .putNumber("scope_volatility", sprint.scopeVolatility())
                .putNumber("story_count", sprint.storyCount())
                .putNumber("budget_amount", budget)
                .putNumber("planned_cost_amount", sprint.plannedCostAmount())
                .put("currency", project.currency() == null ? "USD" : project.currency());

        AiPrediction prediction = inference.infer(new AiInferenceService.InferenceCommand(
                effectiveStage.getModelCode(), EntityType.SPRINT, sprintId,
                sprint.projectId(), project.workspaceId(), vector));

        if (!prediction.isUsable() || prediction.getPredictedValue() == null) {
            return null;
        }

        BigDecimal amount = prediction.getPredictedValue().setScale(2, RoundingMode.HALF_UP);
        BigDecimal lower = scaled(prediction.getLowerBound());
        BigDecimal upper = scaled(prediction.getUpperBound());
        BigDecimal margin = upper != null && lower != null
                ? upper.subtract(lower).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
                : null;

        BigDecimal variancePct = budget != null && budget.compareTo(BigDecimal.ZERO) > 0
                ? amount.subtract(budget).multiply(BigDecimal.valueOf(100))
                .divide(budget, 2, RoundingMode.HALF_UP)
                : null;

        CostForecast forecast = CostForecast.builder()
                .projectId(sprint.projectId())
                .sprintId(sprintId)
                .stage(effectiveStage)
                .forecastAmount(amount)
                .errorMarginAmount(margin)
                .lowerBound(lower)
                .upperBound(upper)
                .currency(project.currency() == null ? "USD" : project.currency())
                .confidence(prediction.getConfidence())
                .trend(trendAgainstPrevious(sprintId, amount))
                .varianceVsBudgetPct(variancePct)
                .inputs(vector.asMap())
                .drivers(prediction.getDrivers())
                .explanation(prediction.getExplanation())
                .predictionId(prediction.getId())
                .build();
        forecast.setOrganizationId(TenantContext.requireOrganizationId());

        return CostForecastResponse.from(forecasts.save(forecast), budget);
    }

    @Transactional(readOnly = true)
    public CostForecastResponse latest(UUID sprintId) {
        return forecasts.findFirstBySprintIdOrderBySnapshotAtDesc(sprintId)
                .map(forecast -> CostForecastResponse.from(forecast, null))
                .orElse(null);
    }

    /**
     * Forecast history for one sprint — the narrowing-margin chart.
     *
     * <p>Reading this sequence is the point of storing every forecast rather than
     * overwriting one row: a projection that has moved from $40k ± $11k to $46k ± $3k
     * over ten days tells a manager both that costs rose and that the number is now
     * worth acting on.</p>
     */
    @Transactional(readOnly = true)
    public List<CostTrendPoint> sprintHistory(UUID sprintId) {
        return forecasts.findBySprintIdOrderBySnapshotAtAsc(sprintId).stream()
                .map(forecast -> new CostTrendPoint(forecast.getSprintId(), null, forecast.getStage(),
                        forecast.getForecastAmount(), forecast.getLowerBound(), forecast.getUpperBound(),
                        null, forecast.getSnapshotAt()))
                .toList();
    }

    /** Latest forecast per sprint across a project — the cost trend report. */
    @Transactional(readOnly = true)
    public List<CostTrendPoint> projectTrend(UUID projectId, Map<UUID, String> sprintNames) {
        return forecasts.findLatestPerSprint(projectId).stream()
                .map(forecast -> new CostTrendPoint(forecast.getSprintId(),
                        sprintNames == null ? null : sprintNames.get(forecast.getSprintId()),
                        forecast.getStage(), forecast.getForecastAmount(), forecast.getLowerBound(),
                        forecast.getUpperBound(), null, forecast.getSnapshotAt()))
                .toList();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Direction against the previous forecast for the same sprint.
     *
     * <p>Movements under 2% are reported as flat. Forecasts wobble by small amounts
     * every time anyone logs an hour, and an arrow that flips daily is noise the reader
     * learns to ignore — at which point a real 15% jump gets ignored too.</p>
     */
    private String trendAgainstPrevious(UUID sprintId, BigDecimal amount) {
        Optional<CostForecast> previous = forecasts.findFirstBySprintIdOrderBySnapshotAtDesc(sprintId);
        if (previous.isEmpty() || previous.get().getForecastAmount() == null
                || previous.get().getForecastAmount().compareTo(BigDecimal.ZERO) == 0) {
            return CostForecast.TREND_FLAT;
        }
        BigDecimal before = previous.get().getForecastAmount();
        BigDecimal changePct = amount.subtract(before).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(before, 2, RoundingMode.HALF_UP);
        if (changePct.compareTo(BigDecimal.valueOf(2)) < 0) {
            return CostForecast.TREND_FLAT;
        }
        return amount.compareTo(before) > 0 ? CostForecast.TREND_UP : CostForecast.TREND_DOWN;
    }

    private BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
