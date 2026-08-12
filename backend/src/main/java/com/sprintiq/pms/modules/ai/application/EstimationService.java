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
                // ---- What the M1 model actually reads --------------------------
                // Names match the trained columns exactly. The service maps them onto
                // the 184-column matrix; a typo here would not error, it would silently
                // fall back to a training median, so these are worth being careful with.
                .put("title", features.title())
                .put("description", features.description())
                .put("acceptance_criteria", features.acceptanceCriteria())
                .put("story_type", features.type())
                .put("domain", project.domain())
                .put("tech_stack", project.techStack())
                .put("dev_experience_level", project.devExperienceLevel())
                .put("team_size", project.teamSize())
                .put("sprint_duration_days", project.sprintDurationDays())
                .put("team_velocity_avg", project.teamVelocityAvg())
                .put("recent_completion_rate", project.recentCompletionRate())
                // Derived from what this project already knows, rather than asked for:
                // acceptance criteria are the closest thing to a component count, and
                // dependencies are how a story reaches outside itself.
                .putNumber("num_components", Math.max(1, features.criteriaCount()))
                .putNumber("external_apis", features.dependencyCount())
                .putNumber("similar_task_count", features.comparableCount())
                .putNumber("task_age_days", features.ageInDays())

                // ---- Retained for the mock client and the stored feature record ----
                // The mock scores on these, and every prediction persists its whole
                // vector, so dropping them would make old predictions unexplainable.
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
                // ---- What the velocity model actually reads ---------------------
                // Names match the trained columns. The service engineers four more
                // (velocity_delta, effective_capacity, stress_score, velocity_per_dev)
                // from these rather than trusting us to, so the row it scores is
                // always self-consistent.
                // put(), not putNumber(): putNumber normalises null to zero so the mock
                // never sees a gap, and for this model zero is the most damaging value
                // available. A sprint with no team size recorded would be scored as a
                // team of nobody at 0% availability rather than as a typical team — and
                // because the key would be present, the service's median fallback could
                // never fire. A null is dropped by NON_NULL serialisation, which is
                // exactly the "not supplied" the service is built to handle.
                .put("historical_velocities", sprint.historicalVelocities())
                .put("team_size", teamSizeFor(sprint))
                .put("sprint_duration_days", sprint.durationDays())
                .put("planned_story_points", sprint.committedPoints())
                .put("leave_days", sprint.absenceDays())
                .putNumber("num_bugs", sprint.defectCount())  // a long: zero is real
                .put("developer_availability", availability(sprint))
                .put("completion_rate", project.recentCompletionRate())
                .put("dev_experience_level", project.devExperienceLevel())
                .put("domain", project.domain())

                // ---- Retained for the mock client and the stored feature record ---
                // The mock scores on these, and every prediction persists its whole
                // vector, so dropping them would make old predictions unexplainable.
                .putNumber("rolling_velocity", sprint.rollingVelocity())
                .putNumber("capacity_points", sprint.capacityPoints())
                .putNumber("capacity_hours", sprint.capacityHours())
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
     * The team the sprint was actually staffed with.
     *
     * <p>The planned {@code teamSize} first, because that is what capacity was set
     * against. Falling back to the number of people who touched the work covers the
     * common case of a sprint nobody filled the field in for, and returning null
     * rather than zero matters: the model reads a supplied zero as a team with
     * nobody on it, and an absent value as "assume a typical team".</p>
     */
    private BigDecimal teamSizeFor(SprintFeatureSource.SprintFeatures sprint) {
        if (sprint.teamSize() != null && sprint.teamSize() > 0) {
            return BigDecimal.valueOf(sprint.teamSize());
        }
        return sprint.contributorCount() > 0 ? BigDecimal.valueOf(sprint.contributorCount()) : null;
    }

    /**
     * The fraction of the sprint the team was actually available for.
     *
     * <p>Absence is recorded in person-days, so the denominator is the whole team's
     * days, not one person's. Null when either side is unknown — the model then uses
     * the median availability of its training set instead of being told this team
     * was fully available, which is the more damaging of the two guesses.</p>
     */
    private BigDecimal availability(SprintFeatureSource.SprintFeatures sprint) {
        BigDecimal team = teamSizeFor(sprint);
        if (team == null || sprint.durationDays() == null || sprint.durationDays() <= 0
                || sprint.absenceDays() == null) {
            return null;
        }
        BigDecimal personDays = team.multiply(BigDecimal.valueOf(sprint.durationDays()));
        if (personDays.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal available = BigDecimal.ONE.subtract(
                sprint.absenceDays().divide(personDays, 4, RoundingMode.HALF_UP));
        // The training set spans 0.55–1.00; clamping keeps a data-entry slip from
        // handing the model a value it has never seen.
        return available.max(BigDecimal.valueOf(0.05)).min(BigDecimal.ONE);
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
