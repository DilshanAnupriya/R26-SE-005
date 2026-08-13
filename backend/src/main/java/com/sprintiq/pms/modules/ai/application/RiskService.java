package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.application.port.EffortFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Model 2 — over-budget and requirement-change risk for a sprint.
 *
 * <p>Both classifiers read one shared feature vector. They are asking different
 * questions of the same sprint, and assembling the features twice would let the two
 * widgets on one dashboard disagree about how much scope had changed.</p>
 */
@Service
@RequiredArgsConstructor
public class RiskService {

    private final AiInferenceService inference;
    private final SprintFeatureSource sprints;
    private final ProjectContextSource projects;
    private final EffortFeatureSource effort;
    /** For the story-text readings the requirement-change model shares with M1. */
    private final StoryFeatureSource stories;
    private final RiskAssessmentRepository assessments;

    @Transactional
    public SprintRiskResponse assess(UUID sprintId) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        ProjectContextSource.ProjectContext project = projects.contextFor(sprint.projectId());
        FeatureVector shared = sharedFeatures(sprint, project);

        RiskAssessmentResponse overBudget = assessOne(RiskType.OVER_BUDGET, sprint, project, shared);
        RiskAssessmentResponse requirementChange =
                assessOne(RiskType.REQUIREMENT_CHANGE, sprint, project, shared);

        RiskSeverity highest = highestOf(overBudget, requirementChange);
        return new SprintRiskResponse(sprintId, overBudget, requirementChange, highest);
    }

    @Transactional
    public RiskAssessmentResponse assess(UUID sprintId, RiskType riskType) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        ProjectContextSource.ProjectContext project = projects.contextFor(sprint.projectId());
        return assessOne(riskType, sprint, project, sharedFeatures(sprint, project));
    }

    @Transactional(readOnly = true)
    public SprintRiskResponse latest(UUID sprintId) {
        RiskAssessmentResponse overBudget = assessments
                .findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(sprintId, RiskType.OVER_BUDGET)
                .map(RiskAssessmentResponse::from).orElse(null);
        RiskAssessmentResponse requirementChange = assessments
                .findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(sprintId, RiskType.REQUIREMENT_CHANGE)
                .map(RiskAssessmentResponse::from).orElse(null);
        return new SprintRiskResponse(sprintId, overBudget, requirementChange,
                highestOf(overBudget, requirementChange));
    }

    /** The risk score's history for one sprint — the trend a retrospective reads. */
    @Transactional(readOnly = true)
    public List<RiskAssessmentResponse> history(UUID sprintId, RiskType riskType) {
        return assessments.findBySprintIdAndRiskTypeOrderByCreatedAtAsc(sprintId, riskType).stream()
                .map(RiskAssessmentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RiskAssessmentResponse> forProject(UUID projectId, int limit) {
        return assessments.findLatestForProject(projectId, PageRequest.of(0, limit)).stream()
                .map(RiskAssessmentResponse::from).toList();
    }

    @Transactional
    public RiskAssessmentResponse acknowledge(UUID assessmentId) {
        RiskAssessment assessment = assessments.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("RiskAssessment", assessmentId));
        assessment.acknowledge(TenantContext.requireUserId());
        return RiskAssessmentResponse.from(assessments.save(assessment));
    }

    // ------------------------------------------------------------------ internals

    private FeatureVector sharedFeatures(SprintFeatureSource.SprintFeatures sprint,
                                         ProjectContextSource.ProjectContext project) {
        long effortMinutes = effort.sprintEffortMinutes(sprint.sprintId());
        long overtimeMinutes = effort.sprintOvertimeMinutes(sprint.sprintId());

        // Budgeted minutes come from planned capacity hours; without them the burn ratio
        // is meaningless, so it is left at zero rather than divided by a guess.
        BigDecimal capacityMinutes = sprint.capacityHours() == null
                ? BigDecimal.ZERO
                : sprint.capacityHours().multiply(BigDecimal.valueOf(60));
        BigDecimal burnRatio = capacityMinutes.compareTo(BigDecimal.ZERO) > 0
                ? BigDecimal.valueOf(effortMinutes).divide(capacityMinutes, 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return FeatureVector.create()
                .putNumber("committed_points", sprint.committedPoints())
                .putNumber("completed_points", sprint.completedPoints())
                .putNumber("capacity_points", sprint.capacityPoints())
                .putNumber("capacity_hours", sprint.capacityHours())
                .putNumber("commitment_ratio", sprint.commitmentRatio())
                .putNumber("scope_volatility", sprint.scopeVolatility())
                .putNumber("elapsed_fraction", sprint.elapsedFraction())
                .putNumber("effort_minutes", effortMinutes)
                .putNumber("overtime_minutes", overtimeMinutes)
                .putNumber("effort_burn_ratio", burnRatio)
                .putNumber("story_count", sprint.storyCount())
                .putNumber("completed_story_count", sprint.completedStoryCount())
                .putNumber("blocked_count", sprint.blockedCount())
                .putNumber("defect_count", sprint.defectCount())
                .putNumber("reopened_count", sprint.reopenedCount())
                .putNumber("unestimated_count", sprint.unestimatedCount())
                .putNumber("contributor_count", sprint.contributorCount())
                .putNumber("team_size", sprint.teamSize())
                .putNumber("absence_days", sprint.absenceDays())
                .putNumber("history_depth", sprint.historicalVelocities().size())
                .putNumber("requirement_count", project.requirementCount())
                .putNumber("post_approval_changes", project.postApprovalChanges())
                .putNumber("requirement_churn_rate", project.churnRate())
                .put("has_sprint_goal", sprint.goal() != null && !sprint.goal().isBlank());
    }

    /**
     * The over-budget model's own columns.
     *
     * <p>Everything here is knowable at planning or mid-sprint. The model was trained
     * with the post-hoc columns — actual hours, effort deviation, the final tallies —
     * deliberately excluded, because they are what the label is computed from. Sending
     * an approximation of one would produce a model that scores perfectly and forecasts
     * nothing, so the fields simply are not here.</p>
     *
     * <p>{@code base_velocity} and {@code velocity_rolling_prev} are the same rolling
     * figure the velocity model reads. That is deliberate: two models disagreeing
     * because one of them was shown a different history is a bug that never surfaces,
     * since both answers look reasonable on their own.</p>
     */
    private FeatureVector overBudgetFeatures(SprintFeatureSource.SprintFeatures sprint,
                                             FeatureVector shared) {
        BigDecimal capacityHours = sprint.capacityHours();
        BigDecimal capacityPoints = sprint.capacityPoints();
        BigDecimal hoursPerPoint = positive(capacityHours) && positive(capacityPoints)
                ? capacityHours.divide(capacityPoints, 3, RoundingMode.HALF_UP)
                : null;

        BigDecimal committed = sprint.committedPoints();
        BigDecimal plannedHours = hoursPerPoint != null && committed != null
                ? committed.multiply(hoursPerPoint)
                : null;

        // Average points per story: the closest thing the app records to the
        // "complexity estimated at planning" the model was trained on.
        BigDecimal complexity = sprint.storyCount() > 0 && committed != null
                ? committed.divide(BigDecimal.valueOf(sprint.storyCount()), 3, RoundingMode.HALF_UP)
                : null;

        long overtimeMinutes = effort.sprintOvertimeMinutes(sprint.sprintId());
        BigDecimal fatigue = positive(capacityHours)
                ? BigDecimal.valueOf(overtimeMinutes)
                        .divide(capacityHours.multiply(BigDecimal.valueOf(60)), 3, RoundingMode.HALF_UP)
                        .min(BigDecimal.ONE)
                : null;

        return shared
                .put("team_size", teamSizeFor(sprint))
                .put("base_velocity", sprint.rollingVelocity())
                .put("velocity_rolling_prev", sprint.rollingVelocity())
                .put("velocity_trend", velocityTrend(sprint))
                .put("complexity", complexity)
                .put("hours_per_point", hoursPerPoint)
                .put("sprint_capacity_hours", capacityHours)
                .put("planned_hours", plannedHours)
                .put("committed_story_points", committed)
                // The app records these for the sprint in flight; the model wants the
                // previous sprint's. Named for what they are so the mapping is not
                // mistaken for history the app does not keep.
                .putNumber("prev_blocked_tasks", sprint.blockedCount())
                .putNumber("prev_reopened_tasks", sprint.reopenedCount())
                .putNumber("prev_rework_score", sprint.reopenedCount() + sprint.defectCount())
                .put("fatigue", fatigue)
                .put("scope_added", sprint.addedPoints());
    }

    /**
     * The requirement-change model's own columns.
     *
     * <p>Four of them — average story length, criteria per story, stories with none, and
     * the count of hedging words — come from {@link StoryFeatureSource#storyMetricsForSprint},
     * the same reading of the same story text the story-point model estimates from.
     * Measuring it once and feeding both is the point.</p>
     */
    private FeatureVector requirementChangeFeatures(SprintFeatureSource.SprintFeatures sprint,
                                                    ProjectContextSource.ProjectContext project,
                                                    FeatureVector shared) {
        StoryFeatureSource.SprintStoryMetrics metrics = stories.storyMetricsForSprint(sprint.sprintId());

        // How well specified the sprint is, as the fraction of stories that have any
        // acceptance criteria at all. Null when there are no stories, because zero
        // would read as "documented terribly" rather than "nothing to document".
        BigDecimal docQuality = metrics.storyCount() > 0
                ? BigDecimal.ONE.subtract(BigDecimal.valueOf(metrics.storiesWithoutCriteria())
                        .divide(BigDecimal.valueOf(metrics.storyCount()), 3, RoundingMode.HALF_UP))
                : null;

        // Whether this sprint's predecessor was already flagged High. The model was
        // trained with that flag, and the only honest source for it is this model's
        // own recorded history.
        boolean previouslyHigh = assessments
                .findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(
                        sprint.sprintId(), RiskType.REQUIREMENT_CHANGE)
                .map(assessment -> assessment.getSeverity() == RiskSeverity.HIGH)
                .orElse(false);

        return shared
                .put("project_domain", project.domain())
                .put("project_status", project.status())
                .put("dev_experience_level", project.devExperienceLevel())
                .putNumber("baseline_story_count", metrics.storyCount())
                .put("avg_story_length_words", metrics.averageWords())
                .put("avg_acceptance_criteria_per_story", metrics.averageCriteria())
                .putNumber("stories_without_AC", metrics.storiesWithoutCriteria())
                .putNumber("ambiguity_marker_count", metrics.ambiguityMarkers())
                .put("requirements_doc_quality", docQuality)
                .put("prev_change_requests_count", project.postApprovalChanges())
                .put("prev_requirement_volatility_score", project.churnRate())
                .put("prev_story_change_ratio", sprint.scopeVolatility())
                .putNumber("prev_high_risk_flag", previouslyHigh ? 1 : 0);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal teamSizeFor(SprintFeatureSource.SprintFeatures sprint) {
        if (sprint.teamSize() != null && sprint.teamSize() > 0) {
            return BigDecimal.valueOf(sprint.teamSize());
        }
        return sprint.contributorCount() > 0 ? BigDecimal.valueOf(sprint.contributorCount()) : null;
    }

    /** Last closed sprint's velocity against the one before it. Null with too little history. */
    private BigDecimal velocityTrend(SprintFeatureSource.SprintFeatures sprint) {
        List<BigDecimal> history = sprint.historicalVelocities();
        if (history == null || history.size() < 2) {
            return null;
        }
        return history.get(history.size() - 1).subtract(history.get(history.size() - 2));
    }

    private RiskAssessmentResponse assessOne(RiskType riskType,
                                             SprintFeatureSource.SprintFeatures sprint,
                                             ProjectContextSource.ProjectContext project,
                                             FeatureVector features) {
        // A fresh copy per model. Both risk types are assessed from the same shared
        // vector in one call, so mutating it in place would hand the second model the
        // first one's columns — and since the feature hash is the cache key, the
        // contamination would persist for six hours without anything looking wrong.
        FeatureVector base = FeatureVector.create().putAll(features.asMap());
        FeatureVector vector = riskType == RiskType.OVER_BUDGET
                ? overBudgetFeatures(sprint, base)
                : requirementChangeFeatures(sprint, project, base);

        AiPrediction prediction = inference.infer(new AiInferenceService.InferenceCommand(
                riskType.getModelCode(), EntityType.SPRINT, sprint.sprintId(),
                sprint.projectId(), project.workspaceId(), vector));

        if (!prediction.isUsable() || prediction.getPredictedValue() == null) {
            return null;
        }

        BigDecimal score = prediction.getPredictedValue();
        List<String> mitigations = new ArrayList<>();
        Object raw = prediction.getRawResponse() == null ? null : prediction.getRawResponse().get("mitigations");
        if (raw instanceof List<?> list) {
            list.forEach(item -> mitigations.add(String.valueOf(item)));
        }

        RiskAssessment assessment = RiskAssessment.builder()
                .projectId(sprint.projectId())
                .sprintId(sprint.sprintId())
                .riskType(riskType)
                .riskScore(score)
                .riskLabel(atRisk(prediction, score))
                .severity(severityOf(score))
                .confidence(prediction.getConfidence())
                .drivers(prediction.getDrivers())
                .mitigations(mitigations)
                .explanation(prediction.getExplanation())
                .predictionId(prediction.getId())
                .build();
        assessment.setOrganizationId(TenantContext.requireOrganizationId());

        return RiskAssessmentResponse.from(assessments.save(assessment));
    }

    /**
     * Score to severity. The bands are wide on purpose: a classifier that has not been
     * calibrated against outcomes cannot justify finer distinctions, and four labels are
     * as much as a dashboard can usefully colour anyway.
     */
    /**
     * Whether the model is calling this sprint at risk.
     *
     * <p>The mock says so in words. The CatBoost models answer with a probability from
     * their binary head, and 0.5 is the threshold that head's own {@code predict} uses,
     * so reading the number is the same question asked numerically rather than a new
     * threshold invented here.</p>
     *
     * <p>This is deliberately a different question from {@link #severityOf}: severity is
     * a band for colouring a dashboard, and "at risk" is the model's own binary call.
     * A 0.48 sprint shows as MEDIUM without being flagged, which is the honest reading
     * of a model that is not sure.</p>
     */
    // Package-private rather than private so the vocabulary mismatch this exists to
    // fix can be pinned by a test without standing up the whole service.
    boolean atRisk(AiPrediction prediction, BigDecimal score) {
        if ("AT_RISK".equals(prediction.getPredictedLabel())) {
            return true;
        }
        return score != null && score.compareTo(BigDecimal.valueOf(0.5)) >= 0;
    }

    RiskSeverity severityOf(BigDecimal score) {
        double value = score.doubleValue();
        if (value >= 0.80) return RiskSeverity.CRITICAL;
        if (value >= 0.60) return RiskSeverity.HIGH;
        if (value >= 0.35) return RiskSeverity.MEDIUM;
        return RiskSeverity.LOW;
    }

    private RiskSeverity highestOf(RiskAssessmentResponse... responses) {
        RiskSeverity highest = RiskSeverity.LOW;
        for (RiskAssessmentResponse response : responses) {
            if (response != null && response.severity() != null
                    && response.severity().ordinal() > highest.ordinal()) {
                highest = response.severity();
            }
        }
        return highest;
    }
}
