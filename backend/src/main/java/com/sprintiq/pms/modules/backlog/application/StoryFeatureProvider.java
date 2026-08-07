package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.WorkItemFeatureSource;
import com.sprintiq.pms.modules.backlog.domain.*;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.EstimateStoryRequest;
import com.sprintiq.pms.modules.projects.application.LabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
                median(allPoints), median(comparable), comparable.size());
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
}
