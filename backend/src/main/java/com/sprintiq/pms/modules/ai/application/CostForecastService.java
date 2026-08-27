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
        List<DeveloperFeatureSource.DeveloperFeatures> team = developers.candidatesFor(sprint.projectId());
        long effortMinutes = effort.sprintEffortMinutes(sprintId);
        long overtimeMinutes = effort.sprintOvertimeMinutes(sprintId);
        long plannedEffortMinutes = effort.sprintPlannedEstimateMinutes(sprintId);
        long sprintTaskCount = effort.sprintTaskCount(sprintId);
        long totalTasks = sprintTaskCount > 0 ? sprintTaskCount : Math.max(sprint.storyCount(), 1L);
        long concurrentAssignments = effort.concurrentAssignments(sprint.projectId());
        BigDecimal budget = sprint.budgetAmount() != null ? sprint.budgetAmount() : project.budgetAmount();
        BigDecimal overtimeHours = BigDecimal.valueOf(overtimeMinutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal actualCostSoFar = actualCost(
                effort.sprintEffortByUser(sprintId), team, blendedRate);
        BigDecimal availabilityRate = averageAvailability(team);
        BigDecimal skillMatchRatio = skillMatchRatio(effort.sprintRequiredSkills(sprintId), team);
        BigDecimal avgSeniority = averageSeniority(team);
        int modelTeamSize = sprint.teamSize() != null && sprint.teamSize() > 0
                ? sprint.teamSize()
                : Math.max(team.size(), 1);

        // A mid-sprint forecast asked for on day one has nothing to extrapolate from;
        // answering it with the initial model is more honest than dividing by a rounding
        // error and reporting a confident nonsense figure.
        CostStage effectiveStage = stage == CostStage.MID_SPRINT
                && sprint.elapsedFraction().compareTo(MIN_ELAPSED_FOR_MID_SPRINT) < 0
                ? CostStage.INITIAL
                : stage;

        FeatureVector vector = FeatureVector.create()
                // Features used by the trained SprintCost initial and mid-sprint bundles.
                .put("team_id", project.key())
                .putNumber("total_tasks", totalTasks)
                .putNumber("planned_story_points", sprint.committedPoints())
                .putNumber("planned_effort_minutes", plannedEffortMinutes)
                .putNumber("completed_story_points", sprint.completedPoints())
                .putNumber("total_effort_minutes", effortMinutes)
                .putNumber("sprint_number", sprint.sequenceNo())
                .putNumber("historical_velocity_avg", sprint.rollingVelocity())
                .putNumber("developer_availability_rate", availabilityRate)
                .putNumber("absence_days_total", sprint.absenceDays())
                .putNumber("overtime_hours_total", overtimeHours)
                .putNumber("concurrent_assignments", concurrentAssignments)
                .putNumber("skill_match_ratio", skillMatchRatio)
                .putNumber("avg_seniority_level", avgSeniority)
                // Compatibility and audit fields used by the existing mock provider and UI.
                .putNumber("capacity_hours", sprint.capacityHours())
                .putNumber("capacity_points", sprint.capacityPoints())
                .putNumber("team_size", modelTeamSize)
                .putNumber("duration_days", sprint.durationDays())
                .putNumber("blended_hourly_rate", blendedRate)
                .putNumber("effort_minutes", effortMinutes)
                .putNumber("overtime_hours", overtimeHours)
                .putNumber("absence_days", sprint.absenceDays())
                .putNumber("elapsed_fraction", sprint.elapsedFraction())
                .putNumber("committed_points", sprint.committedPoints())
                .putNumber("completed_points", sprint.completedPoints())
                .putNumber("scope_volatility", sprint.scopeVolatility())
                .putNumber("story_count", sprint.storyCount())
                .putNumber("budget_amount", budget)
                .putNumber("planned_cost_amount", sprint.plannedCostAmount())
                .putNumber("actual_cost_so_far", actualCostSoFar)
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
                        forecast.getForecastAmount(), forecast.getErrorMarginAmount(),
                        forecast.getLowerBound(), forecast.getUpperBound(), null,
                        forecast.getConfidence(),
                        forecast.getDrivers().stream().map(PredictionDriverResponse::from).toList(),
                        forecast.getExplanation(), forecast.getPredictionId(), forecast.getSnapshotAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CostValidationResponse validateSprint(UUID sprintId) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        BigDecimal blendedRate = developers.averageHourlyRate(sprint.projectId());
        List<DeveloperFeatureSource.DeveloperFeatures> team =
                developers.candidatesFor(sprint.projectId());
        long effortMinutes = effort.sprintEffortMinutes(sprintId);
        BigDecimal actualCostSoFar = actualCost(
                effort.sprintEffortByUser(sprintId), team, blendedRate);
        boolean accuracyEligible = "COMPLETED".equalsIgnoreCase(sprint.status());
        BigDecimal estimatedRemainingCost = accuracyEligible
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : actualCost(effort.sprintRemainingEstimateMinutes(sprintId), BigDecimal.ZERO, blendedRate);
        BigDecimal projectedFinalCost = actualCostSoFar.add(estimatedRemainingCost)
                .setScale(2, RoundingMode.HALF_UP);

        List<CostForecast> storedForecasts = forecasts.findBySprintIdOrderBySnapshotAtAsc(sprintId);
        BigDecimal latestForecastAmount = storedForecasts.isEmpty()
                ? null
                : storedForecasts.get(storedForecasts.size() - 1).getForecastAmount();
        BigDecimal liveVariance = latestForecastAmount == null
                ? null
                : projectedFinalCost.subtract(latestForecastAmount).setScale(2, RoundingMode.HALF_UP);

        List<CostPredictionAccuracy> rows = accuracyEligible
                ? storedForecasts.stream().map(forecast -> accuracy(forecast, actualCostSoFar)).toList()
                : List.of();

        CostPredictionAccuracy initial = latestOf(rows, CostStage.INITIAL);
        CostPredictionAccuracy midSprint = latestOf(rows, CostStage.MID_SPRINT);
        CostStage closerStage = closerStage(initial, midSprint);

        BigDecimal mae = rows.isEmpty() ? null : rows.stream()
                .map(CostPredictionAccuracy::absoluteError)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
        BigDecimal mse = rows.isEmpty() ? null : rows.stream()
                .map(row -> row.absoluteError().multiply(row.absoluteError()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rows.size()), 6, RoundingMode.HALF_UP);
        BigDecimal rmse = mse == null ? null : BigDecimal.valueOf(Math.sqrt(mse.doubleValue()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal mape = rows.isEmpty() || actualCostSoFar.compareTo(BigDecimal.ZERO) == 0 ? null : rows.stream()
                .map(CostPredictionAccuracy::absolutePercentageError)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);

        return new CostValidationResponse(sprintId, evaluationStatus(sprint.status()), accuracyEligible,
                actualCostSoFar, actualCostSoFar, estimatedRemainingCost, projectedFinalCost,
                latestForecastAmount, liveVariance, mae, rmse, mape, initial, midSprint,
                closerStage, rows);
    }

    /** Latest forecast per sprint across a project — the cost trend report. */
    @Transactional(readOnly = true)
    public List<CostTrendPoint> projectTrend(UUID projectId, Map<UUID, String> sprintNames) {
        return forecasts.findLatestPerSprint(projectId).stream()
                .map(forecast -> new CostTrendPoint(forecast.getSprintId(),
                        sprintNames == null ? null : sprintNames.get(forecast.getSprintId()),
                        forecast.getStage(), forecast.getForecastAmount(),
                        forecast.getErrorMarginAmount(), forecast.getLowerBound(),
                        forecast.getUpperBound(), null, forecast.getConfidence(),
                        forecast.getDrivers().stream().map(PredictionDriverResponse::from).toList(),
                        forecast.getExplanation(), forecast.getPredictionId(), forecast.getSnapshotAt()))
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

    private BigDecimal actualCost(long effortMinutes, BigDecimal overtimeHours, BigDecimal hourlyRate) {
        BigDecimal hours = BigDecimal.valueOf(effortMinutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal base = hours.multiply(hourlyRate);
        BigDecimal overtimePremium = overtimeHours.multiply(hourlyRate).multiply(BigDecimal.valueOf(0.5));
        return base.add(overtimePremium).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal actualCost(
            List<EffortFeatureSource.UserEffort> effortByUser,
            List<DeveloperFeatureSource.DeveloperFeatures> team,
            BigDecimal fallbackRate) {
        return effortByUser.stream()
                .map(row -> {
                    BigDecimal rate = team.stream()
                            .filter(member -> member.userId().equals(row.userId()))
                            .map(DeveloperFeatureSource.DeveloperFeatures::hourlyRate)
                            .filter(candidate -> candidate != null
                                    && candidate.compareTo(BigDecimal.ZERO) > 0)
                            .findFirst()
                            .orElse(fallbackRate);
                    BigDecimal overtimeHours = BigDecimal.valueOf(row.overtimeMinutes())
                            .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
                    return actualCost(row.minutes(), overtimeHours, rate);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private CostPredictionAccuracy accuracy(CostForecast forecast, BigDecimal actualCost) {
        BigDecimal predicted = forecast.getForecastAmount();
        BigDecimal error = actualCost.subtract(predicted).setScale(2, RoundingMode.HALF_UP);
        BigDecimal absolute = error.abs().setScale(2, RoundingMode.HALF_UP);
        BigDecimal percentage = actualCost.compareTo(BigDecimal.ZERO) == 0
                ? null
                : absolute.multiply(BigDecimal.valueOf(100))
                .divide(actualCost, 2, RoundingMode.HALF_UP);
        return new CostPredictionAccuracy(forecast.getId(), forecast.getStage(), predicted,
                actualCost, error, absolute, percentage, forecast.getSnapshotAt());
    }

    private CostPredictionAccuracy latestOf(List<CostPredictionAccuracy> rows, CostStage stage) {
        return rows.stream()
                .filter(row -> row.stage() == stage)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private CostStage closerStage(CostPredictionAccuracy initial, CostPredictionAccuracy midSprint) {
        if (initial == null && midSprint == null) return null;
        if (initial == null) return CostStage.MID_SPRINT;
        if (midSprint == null) return CostStage.INITIAL;
        return midSprint.absoluteError().compareTo(initial.absoluteError()) <= 0
                ? CostStage.MID_SPRINT
                : CostStage.INITIAL;
    }

    private String evaluationStatus(String sprintStatus) {
        if (sprintStatus == null) return "PLANNING";
        return switch (sprintStatus.toUpperCase(java.util.Locale.ROOT)) {
            case "COMPLETED" -> "FINAL";
            case "ACTIVE" -> "LIVE_TRACKING";
            case "CANCELLED" -> "CANCELLED";
            default -> "PLANNING";
        };
    }

    private BigDecimal averageAvailability(List<DeveloperFeatureSource.DeveloperFeatures> team) {
        if (team.isEmpty()) {
            return BigDecimal.ONE;
        }
        BigDecimal total = team.stream()
                .map(member -> member.availabilityRate() == null ? BigDecimal.ONE : member.availabilityRate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(team.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal skillMatchRatio(List<String> requiredSkills,
                                       List<DeveloperFeatureSource.DeveloperFeatures> team) {
        List<String> distinct = requiredSkills == null ? List.of() : requiredSkills.stream()
                .filter(skill -> skill != null && !skill.isBlank())
                .map(skill -> skill.toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return BigDecimal.ONE;
        }
        long covered = distinct.stream()
                .filter(skill -> team.stream()
                        .anyMatch(member -> member.skills().getOrDefault(skill, (short) 0) >= 3))
                .count();
        return BigDecimal.valueOf(covered)
                .divide(BigDecimal.valueOf(distinct.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal averageSeniority(List<DeveloperFeatureSource.DeveloperFeatures> team) {
        if (team.isEmpty()) {
            return BigDecimal.valueOf(2);
        }
        BigDecimal total = team.stream()
                .map(member -> BigDecimal.valueOf(seniorityLevel(member.seniority())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(team.size()), 2, RoundingMode.HALF_UP);
    }

    private int seniorityLevel(String seniority) {
        if (seniority == null) {
            return 2;
        }
        return switch (seniority) {
            case "INTERN", "JUNIOR" -> 1;
            case "MID" -> 2;
            case "SENIOR" -> 3;
            case "LEAD" -> 4;
            case "PRINCIPAL" -> 5;
            default -> 2;
        };
    }
}
