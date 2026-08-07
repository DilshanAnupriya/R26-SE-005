package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.AiPrediction;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.security.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Model 1 — story point estimation and sprint velocity prediction.
 *
 * <p>Estimation is advisory by construction. The suggestion is written to the story's
 * {@code ai*} columns and nowhere else; {@code storyPoints} only changes when a person
 * accepts it. Teams abandon estimation tools that silently move their numbers, and the
 * one thing this feature cannot afford is to be distrusted during planning.</p>
 */
@Service
@RequiredArgsConstructor
public class EstimationService {

    private final AiInferenceService inference;
    private final StoryFeatureSource stories;
    private final SprintFeatureSource sprints;
    private final ProjectContextSource projects;
    private final AppProperties properties;

    // -------------------------------------------------------------- story points

    @Transactional
    public StoryPointEstimateResponse estimate(UUID storyId) {
        StoryFeatureSource.StoryFeatures features = stories.featuresFor(storyId);
        return estimateFrom(features);
    }

    /**
     * Batch estimation for a whole backlog view.
     *
     * <p>One story failing does not fail the batch. A backlog of forty items where
     * story nineteen has a malformed description should still come back with
     * thirty-nine estimates — returning nothing because one row misbehaved makes the
     * feature useless precisely when it is most useful.</p>
     */
    @Transactional
    public BatchEstimateResponse estimateBatch(List<UUID> storyIds) {
        List<UUID> capped = storyIds.size() > properties.getAi().getMaxBatchSize()
                ? storyIds.subList(0, properties.getAi().getMaxBatchSize())
                : storyIds;

        List<StoryFeatureSource.StoryFeatures> batch = stories.featuresFor(capped);
        List<StoryPointEstimateResponse> results = new ArrayList<>(batch.size());
        int succeeded = 0;

        for (StoryFeatureSource.StoryFeatures features : batch) {
            try {
                StoryPointEstimateResponse estimate = estimateFrom(features);
                results.add(estimate);
                if (estimate.suggestedPoints() != null) succeeded++;
            } catch (RuntimeException ex) {
                results.add(new StoryPointEstimateResponse(features.storyId(), features.itemKey(),
                        features.title(), features.currentPoints(), null, null));
            }
        }
        return new BatchEstimateResponse(results, capped.size(), succeeded);
    }

    private StoryPointEstimateResponse estimateFrom(StoryFeatureSource.StoryFeatures features) {
        ProjectContextSource.ProjectContext project = projects.contextFor(features.projectId());

        FeatureVector vector = FeatureVector.create()
                .put("story_type", features.type())
                .put("priority", features.priority().name())
                .putNumber("acceptance_criteria_count", features.criteriaCount())
                .putNumber("word_count", features.wordCount())
                .putNumber("title_length", features.title() == null ? 0 : features.title().length())
                .put("has_description", features.description() != null && !features.description().isBlank())
                .put("has_acceptance_criteria",
                        features.acceptanceCriteria() != null && !features.acceptanceCriteria().isBlank())
                .putNumber("label_count", features.labelCount())
                .putNumber("dependency_count", features.dependencyCount())
                .putNumber("task_count", features.taskCount())
                .put("has_epic", features.hasEpic())
                .putNumber("project_median_points", features.projectMedianPoints())
                .putNumber("comparable_median_points", features.comparableMedianPoints())
                .putNumber("comparable_count", features.comparableCount());

        AiPrediction prediction = inference.infer(new AiInferenceService.InferenceCommand(
                AiModelCode.M1_STORY_POINTS, EntityType.STORY, features.storyId(),
                features.projectId(), project.workspaceId(), vector));

        if (prediction.isUsable() && prediction.getPredictedValue() != null) {
            stories.recordSuggestion(features.storyId(), prediction.getId(),
                    prediction.getPredictedValue(), prediction.getConfidence(),
                    prediction.getLowerBound(), prediction.getUpperBound());
        }

        return new StoryPointEstimateResponse(features.storyId(), features.itemKey(), features.title(),
                features.currentPoints(), prediction.getPredictedValue(),
                PredictionEnvelope.from(prediction));
    }

    // ------------------------------------------------------------------ velocity

    /**
     * Predicts next-sprint velocity and judges the current commitment against it.
     *
     * <p>The verdict is the point. A velocity number on its own invites a team to
     * congratulate itself; "you have committed to 34 points and your predicted velocity
     * is 22" is a conversation that changes what happens next.</p>
     */
    @Transactional
    public VelocityPredictionResponse predictVelocity(UUID sprintId) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        ProjectContextSource.ProjectContext project = projects.contextFor(sprint.projectId());

        FeatureVector vector = FeatureVector.create()
                .put("historical_velocities", sprint.historicalVelocities())
                .putNumber("rolling_velocity", sprint.rollingVelocity())
                .putNumber("capacity_points", sprint.capacityPoints())
                .putNumber("capacity_hours", sprint.capacityHours())
                .putNumber("team_size", sprint.teamSize())
                .putNumber("duration_days", sprint.durationDays())
                .putNumber("absence_days", sprint.absenceDays())
                .putNumber("committed_points", sprint.committedPoints())
                .putNumber("story_count", sprint.storyCount())
                .putNumber("unestimated_count", sprint.unestimatedCount())
                .putNumber("contributor_count", sprint.contributorCount());

        AiPrediction prediction = inference.infer(new AiInferenceService.InferenceCommand(
                AiModelCode.M1_VELOCITY, EntityType.SPRINT, sprintId,
                sprint.projectId(), project.workspaceId(), vector));

        BigDecimal predicted = prediction.getPredictedValue();
        BigDecimal ratio = null;
        String verdict = "UNKNOWN";
        if (predicted != null && predicted.compareTo(BigDecimal.ZERO) > 0) {
            ratio = sprint.committedPoints().divide(predicted, 3, RoundingMode.HALF_UP);
            verdict = commitmentVerdict(ratio);
        }

        return new VelocityPredictionResponse(sprintId, predicted, sprint.rollingVelocity(),
                sprint.committedPoints(), sprint.capacityPoints(), ratio, verdict,
                PredictionEnvelope.from(prediction));
    }

    /**
     * Bands rather than a raw ratio, because the ratio's precision is fictitious.
     *
     * <p>Slightly under predicted velocity is the healthy place to be — a commitment
     * that exactly matches the prediction leaves no room for the discovery that always
     * happens, and one comfortably below it is a team sandbagging.</p>
     */
    private String commitmentVerdict(BigDecimal ratio) {
        double value = ratio.doubleValue();
        if (value > 1.25) return "OVER_COMMITTED";
        if (value > 1.05) return "STRETCHED";
        if (value >= 0.75) return "BALANCED";
        return "UNDER_COMMITTED";
    }
}
