package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.CostStage;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.modules.settings.application.FeatureFlagService;
import com.sprintiq.pms.security.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the AI dashboard for a project or a sprint.
 *
 * <p>Every capability here is optional. A workspace with cost forecasting switched off,
 * or a project whose model calls are failing, still gets a page — with the missing
 * capabilities named in {@code disabledCapabilities} rather than rendered as empty
 * boxes. A dashboard that silently omits a panel is one users assume is broken.</p>
 *
 * <p>Reads are cheap and writes are not, so this service prefers stored assessments over
 * fresh inference: opening the dashboard should not invoke four models and bill for
 * them. Only the velocity prediction is computed live, because it is the one figure a
 * planner is looking at the page to obtain.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiInsightsService {

    /** Sprints included in a project-level cost and risk history. */
    private static final int TREND_WINDOW = 8;

    private final EstimationService estimation;
    private final RiskService risk;
    private final CostForecastService cost;
    private final SprintFeatureSource sprints;
    private final ProjectContextSource projects;
    private final StoryFeatureSource stories;
    private final FeatureFlagService featureFlags;
    private final AppProperties properties;

    @Transactional
    public AiInsightsResponse forSprint(UUID sprintId) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        ProjectContextSource.ProjectContext project = projects.contextFor(sprint.projectId());
        List<String> disabled = disabledCapabilities(project.workspaceId());

        VelocityPredictionResponse velocity = capability(AiModelCode.M1_VELOCITY, disabled,
                () -> estimation.predictVelocity(sprintId));
        SprintRiskResponse sprintRisk = capability(AiModelCode.M2_OVER_BUDGET, disabled,
                () -> risk.latest(sprintId));
        CostForecastResponse costForecast = capability(AiModelCode.M4_COST_INITIAL, disabled,
                () -> cost.latest(sprintId));
        List<CostTrendPoint> costHistory = capability(AiModelCode.M4_COST_INITIAL, disabled,
                () -> cost.sprintHistory(sprintId));

        return new AiInsightsResponse(sprintId, "SPRINT", sprint.name(), velocity, sprintRisk,
                costForecast, costHistory == null ? List.of() : costHistory, List.of(),
                null, disabled);
    }

    @Transactional
    public AiInsightsResponse forProject(UUID projectId) {
        ProjectContextSource.ProjectContext project = projects.contextFor(projectId);
        List<String> disabled = disabledCapabilities(project.workspaceId());

        List<UUID> recentSprints = sprints.recentSprintIds(projectId, TREND_WINDOW);
        Map<UUID, String> sprintNames = new LinkedHashMap<>();
        for (UUID sprintId : recentSprints) {
            try {
                sprintNames.put(sprintId, sprints.featuresFor(sprintId).name());
            } catch (RuntimeException ex) {
                log.debug("Skipping sprint {} in project insights: {}", sprintId, ex.toString());
            }
        }

        UUID currentSprint = recentSprints.isEmpty() ? null : recentSprints.get(0);

        VelocityPredictionResponse velocity = currentSprint == null ? null
                : capability(AiModelCode.M1_VELOCITY, disabled,
                () -> estimation.predictVelocity(currentSprint));
        SprintRiskResponse sprintRisk = currentSprint == null ? null
                : capability(AiModelCode.M2_OVER_BUDGET, disabled, () -> risk.latest(currentSprint));
        CostForecastResponse costForecast = currentSprint == null ? null
                : capability(AiModelCode.M4_COST_INITIAL, disabled, () -> cost.latest(currentSprint));

        List<CostTrendPoint> trend = capability(AiModelCode.M4_COST_INITIAL, disabled,
                () -> cost.projectTrend(projectId, sprintNames));
        List<RiskAssessmentResponse> recentRisks = capability(AiModelCode.M2_OVER_BUDGET, disabled,
                () -> risk.forProject(projectId, TREND_WINDOW * 2));

        return new AiInsightsResponse(projectId, "PROJECT", project.name(), velocity, sprintRisk,
                costForecast, trend == null ? List.of() : trend,
                recentRisks == null ? List.of() : recentRisks,
                coverage(projectId), disabled);
    }

    /** Forces a fresh assessment of every enabled capability for a sprint. */
    @Transactional
    public AiInsightsResponse refreshSprint(UUID sprintId) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        ProjectContextSource.ProjectContext project = projects.contextFor(sprint.projectId());
        List<String> disabled = disabledCapabilities(project.workspaceId());

        capability(AiModelCode.M2_OVER_BUDGET, disabled, () -> risk.assess(sprintId));
        capability(AiModelCode.M4_COST_INITIAL, disabled, () -> cost.forecast(sprintId,
                sprint.elapsedFraction().compareTo(BigDecimal.ZERO) > 0
                        ? CostStage.MID_SPRINT : CostStage.INITIAL));

        return forSprint(sprintId);
    }

    // ------------------------------------------------------------------ internals

    private EstimationCoverageResponse coverage(UUID projectId) {
        StoryFeatureSource.EstimationCoverage raw = stories.estimationCoverage(projectId);
        BigDecimal coveragePct = raw.totalStories() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(raw.estimated() * 100L)
                .divide(BigDecimal.valueOf(raw.totalStories()), 1, RoundingMode.HALF_UP);
        BigDecimal aiRate = raw.estimated() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(raw.aiAssisted() * 100L)
                .divide(BigDecimal.valueOf(raw.estimated()), 1, RoundingMode.HALF_UP);
        return new EstimationCoverageResponse(raw.totalStories(), raw.estimated(), raw.aiAssisted(),
                coveragePct, aiRate);
    }

    private List<String> disabledCapabilities(UUID workspaceId) {
        List<String> disabled = new ArrayList<>();
        if (!properties.getAi().isEnabled()) {
            return new ArrayList<>(FeatureFlagService.KNOWN_FLAGS);
        }
        for (String flag : FeatureFlagService.KNOWN_FLAGS) {
            if (!featureFlags.isEnabled(workspaceId, flag)) {
                disabled.add(flag);
            }
        }
        return disabled;
    }

    /**
     * Runs one capability, or returns null when its flag is off or it fails.
     *
     * <p>Swallowing the exception is deliberate and confined to this method. A dashboard
     * assembling six independent panels must not fail wholesale because one model is
     * down — but the failure is still logged, and the panel's absence is still reported
     * to the client through {@code disabledCapabilities}.</p>
     */
    private <T> T capability(AiModelCode code, List<String> disabled, java.util.function.Supplier<T> work) {
        if (disabled.contains(code.getFlagKey())) {
            return null;
        }
        try {
            return work.get();
        } catch (RuntimeException ex) {
            log.warn("AI capability {} unavailable while assembling insights: {}", code, ex.toString());
            if (!disabled.contains(code.getFlagKey())) {
                disabled.add(code.getFlagKey());
            }
            return null;
        }
    }
}
