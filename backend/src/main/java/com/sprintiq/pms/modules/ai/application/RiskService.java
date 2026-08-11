package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.application.port.EffortFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    private RiskAssessmentResponse assessOne(RiskType riskType,
                                             SprintFeatureSource.SprintFeatures sprint,
                                             ProjectContextSource.ProjectContext project,
                                             FeatureVector features) {
        AiPrediction prediction = inference.infer(new AiInferenceService.InferenceCommand(
                riskType.getModelCode(), EntityType.SPRINT, sprint.sprintId(),
                sprint.projectId(), project.workspaceId(), features));

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
                .riskLabel("AT_RISK".equals(prediction.getPredictedLabel()))
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
    private RiskSeverity severityOf(BigDecimal score) {
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
