package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource.StoryBudgetBreach;
import com.sprintiq.pms.modules.ai.application.port.WorkItemFeatureSource;
import com.sprintiq.pms.modules.backlog.domain.*;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.EstimateStoryRequest;
import com.sprintiq.pms.modules.projects.application.LabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes stories to the AI module, and writes accepted estimates back.
 *
 * <p>The backlog owns {@code stories}; the AI module owns the question. This class is
 * the only place the two meet, which is what lets the estimation service be written and
 * tested without importing {@code Story} at all.</p>
 */
@Component
@RequiredArgsConstructor
public class StoryFeatureProvider implements StoryFeatureSource, WorkItemFeatureSource {

    private final StoryRepository storyRepository;
    private final StoryService storyService;
    private final DependencyService dependencyService;
    private final LabelService labelService;
    private final StoryTaskCounter taskCounter;
    private final com.sprintiq.pms.modules.projects.application.ProjectLookupService projectLookup;

    // ------------------------------------------------------------ story features

    @Override
    @Transactional(readOnly = true)
    public StoryFeatures featuresFor(UUID storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", storyId));
        return describe(story, storyRepository.findEstimatedPoints(story.getProjectId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryFeatures> featuresFor(List<UUID> storyIds) {
        List<Story> stories = storyRepository.findByIdInAndDeletedAtIsNull(storyIds);
        if (stories.isEmpty()) {
            return List.of();
        }
        // One baseline scan for the whole batch: forty stories from one project would
        // otherwise run forty identical median queries.
        UUID projectId = stories.get(0).getProjectId();
        List<StoryRepository.EstimatedPoint> baseline = storyRepository.findEstimatedPoints(projectId);

        List<StoryFeatures> features = new ArrayList<>(stories.size());
        for (Story story : stories) {
            List<StoryRepository.EstimatedPoint> scope = story.getProjectId().equals(projectId)
                    ? baseline
                    : storyRepository.findEstimatedPoints(story.getProjectId());
            features.add(describe(story, scope));
        }
        return features;
    }

    @Override
    @Transactional
    public void recordSuggestion(UUID storyId, UUID predictionId, BigDecimal points,
                                 BigDecimal confidence, BigDecimal lowerBound, BigDecimal upperBound) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", storyId));
        story.setAiStoryPoints(points);
        story.setAiConfidence(confidence);
        story.setAiLowerBound(lowerBound);
        story.setAiUpperBound(upperBound);
        story.setAiPredictionId(predictionId);
        // storyPoints and estimateSource are untouched on purpose: a suggestion is not
        // an estimate until someone says so.
        storyRepository.save(story);
    }

    @Override
    @Transactional
    public void applyEstimate(UUID storyId, BigDecimal points, boolean fromModel) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", storyId));
        // Routed through StoryService rather than saved here, so the estimate lands with
        // the same validation and the same activity record as a manual one.
        storyService.estimate(storyId, new EstimateStoryRequest(points,
                fromModel ? EstimateSource.AI_ACCEPTED : EstimateSource.AI_OVERRIDDEN,
                story.getAiPredictionId()));
    }

    @Override
    @Transactional(readOnly = true)
    public EstimationCoverage estimationCoverage(UUID projectId) {
        return new EstimationCoverage(
                storyRepository.countByProjectIdAndDeletedAtIsNull(projectId),
                storyRepository.countByProjectIdAndStoryPointsIsNotNullAndDeletedAtIsNull(projectId),
                storyRepository.countByProjectIdAndEstimateSourceInAndDeletedAtIsNull(projectId,
                        List.of(EstimateSource.AI_ACCEPTED, EstimateSource.AI_OVERRIDDEN)));
    }

    /**
     * The same readings the story-point model takes, aggregated over a sprint.
     *
     * <p>Uses {@link #countCriteria} rather than its own parser so "how many acceptance
     * criteria" means one thing across both models. The risk model asking a different
     * question of the same text than the estimator does is the kind of divergence that
     * produces two defensible answers and no way to tell which is right.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public SprintStoryMetrics storyMetricsForSprint(UUID sprintId) {
        List<Story> stories = storyRepository.findBySprintIdAndDeletedAtIsNull(sprintId);
        if (stories.isEmpty()) {
            return SprintStoryMetrics.empty();
        }

        long totalWords = 0;
        long totalCriteria = 0;
        long withoutCriteria = 0;
        long ambiguity = 0;

        for (Story story : stories) {
            String text = String.join(" ",
                    story.getTitle() == null ? "" : story.getTitle(),
                    story.getDescription() == null ? "" : story.getDescription(),
                    story.getAcceptanceCriteria() == null ? "" : story.getAcceptanceCriteria());
            totalWords += text.isBlank() ? 0 : text.trim().split("\\s+").length;

            int criteria = countCriteria(story.getAcceptanceCriteria());
            totalCriteria += criteria;
            if (criteria == 0) {
                withoutCriteria++;
            }
            ambiguity += countAmbiguityMarkers(text);
        }

        BigDecimal count = BigDecimal.valueOf(stories.size());
        return new SprintStoryMetrics(stories.size(),
                BigDecimal.valueOf(totalWords).divide(count, 2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(totalCriteria).divide(count, 2, RoundingMode.HALF_UP),
                withoutCriteria, ambiguity);
    }

    /**
     * The words that leave a requirement open to interpretation.
     *
     * <p>Kept in step with {@code AMBIGUITY_MARKERS} in the estimation service's
     * {@code risk_features.py}. A word list is a poor proxy for ambiguity in general,
     * but it is exactly the signal the requirement-change model was trained on, and
     * measuring something subtly different here would feed it a feature it has never
     * seen under a name it recognises.</p>
     */
    private long countAmbiguityMarkers(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = AMBIGUITY_MARKERS.matcher(text);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static final Pattern AMBIGUITY_MARKERS = Pattern.compile(
            "\\b(should|maybe|might|possibly|tbd|tbc|approximately|roughly|etc|"
                    + "as needed|if required|to be (decided|confirmed|determined))\\b|\\?",
            Pattern.CASE_INSENSITIVE);

    // -------------------------------------------------------- allocation target

    @Override
    public boolean supports(EntityType entityType) {
        return entityType == EntityType.STORY;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkItemFeatures workItemFor(UUID storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", storyId));
        List<String> skillTags = labelService.labelsFor(EntityType.STORY, storyId).stream()
                .map(label -> label.name().toLowerCase(Locale.ROOT))
                .toList();
        return new WorkItemFeatures(story.getId(), EntityType.STORY, story.getProjectId(),
                story.getSprintId(), story.getItemKey(), story.getTitle(), story.getType().name(),
                story.getPriority(), null, story.getStoryPoints(), skillTags,
                story.getAssigneeId(), story.isBlocked());
    }

    // ------------------------------------------------------------------ internals

    private StoryFeatures describe(Story story, List<StoryRepository.EstimatedPoint> estimated) {
        int criteria = countCriteria(story.getAcceptanceCriteria());

        List<BigDecimal> allPoints = estimated.stream()
                .map(StoryRepository.EstimatedPoint::getPoints)
                .toList();
        List<BigDecimal> comparable = estimated.stream()
                .filter(point -> point.getType() == story.getType())
                .map(StoryRepository.EstimatedPoint::getPoints)
                .toList();

        return new StoryFeatures(story.getId(), story.getProjectId(), story.getItemKey(),
                story.getTitle(), story.getDescription(), story.getAcceptanceCriteria(), criteria,
                story.getType().name(), story.getPriority(),
                labelService.labelsFor(EntityType.STORY, story.getId()).size(),
                dependencyService.list(EntityType.STORY, story.getId()).size(),
                (int) taskCounter.countTasksForStory(story.getId()),
                story.getEpicId() != null, story.getStoryPoints(),
                median(allPoints), median(comparable), comparable.size(), story.getCreatedAt());
    }

    /**
     * Counts acceptance criteria by line.
     *
     * <p>Teams write criteria as bullets, numbered lists or Gherkin, and every one of
     * those is line-separated. Parsing the dialects individually would be brittle for no
     * gain — the model wants "roughly how many things must be true", and the line count
     * answers that for all three.</p>
     */
    private int countCriteria(String criteria) {
        if (criteria == null || criteria.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(criteria.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .count();
    }

    /** Median, not mean: one 21-point epic-in-disguise should not drag the anchor up. */
    private BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle))
                .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Stories in the sprint whose task breakdown outgrew their points.
     *
     * <p>Uses the same {@link StoryTaskBudget} the planner sees when adding a task, so
     * the warning on the form and the penalty on the score are one calculation. If these
     * ever diverged, a team would be charged risk for an overrun the UI told them was
     * fine — and they would be right to stop trusting the number.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<StoryBudgetBreach> budgetBreachesForSprint(UUID sprintId) {
        List<Story> stories = storyRepository.findBySprintIdAndDeletedAtIsNull(sprintId);
        if (stories.isEmpty()) {
            return List.of();
        }
        // One rate for the sprint: every story in it belongs to the same project.
        BigDecimal hoursPerPoint = projectLookup.require(stories.get(0).getProjectId())
                .hoursPerPoint();

        List<StoryBudgetBreach> breaches = new ArrayList<>();
        for (Story story : stories) {
            StoryTaskCounter.StoryTaskEffort effort = taskCounter.effortForStory(story.getId());
            StoryTaskBudget budget = StoryTaskBudget.of(story.getStoryPoints(), hoursPerPoint,
                    effort.estimatedMinutes(), effort.taskCount(),
                    effort.taskCount() - effort.estimatedCount());
            if (budget.isOverBudget()) {
                breaches.add(new StoryBudgetBreach(story.getId(), story.getItemKey(),
                        budget.overrunMinutes(), budget.budgetMinutes()));
            }
        }
        return breaches;
    }
}
