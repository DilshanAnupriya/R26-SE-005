package com.sprintiq.pms.modules.ai.application.port;

import com.sprintiq.pms.common.domain.Priority;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the AI module needs to know about a story, and the one thing it needs to write
 * back.
 *
 * <p>Declared here and implemented by the backlog module — the same inversion as
 * {@code ProjectStatisticsContributor}. The consumer states its requirement; the owner
 * of the data satisfies it. The AI module therefore compiles without any knowledge of
 * {@code Story}, which is what keeps it extractable into its own service later, and
 * what stops feature engineering from quietly turning into a second backlog API.</p>
 */
public interface StoryFeatureSource {

    /**
     * The M1 feature vector for one story.
     *
     * @param storyId               the story
     * @param projectId             owning project, for project-relative baselines
     * @param itemKey               {@code PROJ-142}, so explanations can name the story
     * @param title                 raw title, used for length and keyword signals
     * @param description           may be null
     * @param acceptanceCriteria    may be null
     * @param criteriaCount         parsed acceptance criteria — the strongest size signal
     *                              available before anyone writes code
     * @param type                  STORY / BUG / SPIKE / CHORE, as a string
     * @param priority              business priority
     * @param labelCount            labels attached
     * @param dependencyCount       edges in or out; work that waits on other work is bigger
     *                              than its description suggests
     * @param taskCount             child tasks already broken out
     * @param hasEpic               whether it sits under an epic
     * @param currentPoints         the human estimate, if one exists
     * @param projectMedianPoints   median of the project's estimated stories — the anchor
     *                              a zero-shot model has to have or it invents a scale
     * @param comparableMedianPoints median of same-type, similar-length stories
     * @param comparableCount       how many stories that median was drawn from
     */
    record StoryFeatures(UUID storyId,
                         UUID projectId,
                         String itemKey,
                         String title,
                         String description,
                         String acceptanceCriteria,
                         int criteriaCount,
                         String type,
                         Priority priority,
                         int labelCount,
                         int dependencyCount,
                         int taskCount,
                         boolean hasEpic,
                         BigDecimal currentPoints,
                         BigDecimal projectMedianPoints,
                         BigDecimal comparableMedianPoints,
                         int comparableCount) {

        /** Words across title, description and criteria — the crudest complexity proxy. */
        public int wordCount() {
            int words = countWords(title) + countWords(description) + countWords(acceptanceCriteria);
            return Math.max(words, 1);
        }

        private static int countWords(String text) {
            if (text == null || text.isBlank()) return 0;
            return text.trim().split("\\s+").length;
        }
    }

    StoryFeatures featuresFor(UUID storyId);

    List<StoryFeatures> featuresFor(List<UUID> storyIds);

    /**
     * Records a suggestion against the story without touching {@code storyPoints}.
     *
     * <p>The model never overwrites the human estimate. It writes into the {@code ai*}
     * columns and leaves {@code storyPoints} alone until someone accepts the suggestion,
     * because a planning session where numbers change by themselves is one nobody
     * trusts twice.</p>
     */
    void recordSuggestion(UUID storyId, UUID predictionId, BigDecimal points,
                          BigDecimal confidence, BigDecimal lowerBound, BigDecimal upperBound);

    /** Applies an accepted or overridden estimate as the story's real one. */
    void applyEstimate(UUID storyId, BigDecimal points, boolean fromModel);

    /**
     * How much of a project carries an estimate and how much of that came from the
     * model.
     *
     * @param aiAssisted stories whose current estimate arrived through the AI panel,
     *                   whether accepted outright or adjusted — the honest measure of
     *                   whether the feature is actually being used, as opposed to how
     *                   many suggestions were generated
     */
    record EstimationCoverage(long totalStories, long estimated, long aiAssisted) {}

    EstimationCoverage estimationCoverage(UUID projectId);
}
