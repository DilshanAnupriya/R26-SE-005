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
                         int comparableCount,
                         /** When the story was raised — the model reads backlog age as a signal. */
                         java.time.Instant createdAt,
                         /**
                          * Label names, lower-cased. The count alone cannot say whether a
                          * story is security work; the names can, and the model has three
                          * columns that want exactly that.
                          */
                         List<String> labels) {

        /** Days since the story was raised. Zero when unknown, which is the median-ish case. */
        public int ageInDays() {
            if (createdAt == null) return 0;
            long days = java.time.Duration.between(createdAt, java.time.Instant.now()).toDays();
            return (int) Math.max(0, Math.min(days, 365));
        }

        // The model service has three columns for the kind of work a story is —
        // integration, security, UI. SprintIQ never sent them, so the service fell back
        // to keyword-matching the story text and recorded all three as inferred on every
        // single story. Deriving them here is strictly better: a label is a deliberate
        // human statement about the work, and a dependency edge is a fact, where a word
        // in a sentence is a hint. The keyword pass is kept as the last resort so a
        // story with no labels is no worse off than it was.
        private static final java.util.regex.Pattern INTEGRATION_HINTS =
                java.util.regex.Pattern.compile(
                        // No bare "import" or "export": in a sentence they are usually
                        // the system's own feature — an Export button, a CSV export —
                        // and "Add a tooltip to the Export button" was being sized as
                        // integration work. A person's "import"/"export" label still counts.
                        "\\b(integrat\\w*|webhook|sync|third[- ]party|api|sso|oauth)\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE);
        private static final java.util.regex.Pattern SECURITY_HINTS =
                java.util.regex.Pattern.compile(
                        "\\b(auth\\w*|security|encrypt\\w*|permission|rbac|gdpr|pci|compliance|audit|token)\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE);
        private static final java.util.regex.Pattern UI_HINTS =
                java.util.regex.Pattern.compile(
                        "\\b(ui|ux|screen|page|form|dashboard|layout|responsive|modal|chart|design)\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE);

        /**
         * Whether this story touches something outside the system.
         *
         * <p>A dependency edge counts on its own: work that waits on other work is
         * integration work whatever its wording says.</p>
         */
        public boolean hasIntegrationWork() {
            return dependencyCount > 0
                    || labelMatches("integration", "api", "webhook", "sync", "import",
                            "export", "third-party", "thirdparty", "sso", "oauth")
                    || INTEGRATION_HINTS.matcher(text()).find();
        }

        public boolean hasSecurityWork() {
            return labelMatches("security", "auth", "authentication", "authorization",
                            "permission", "permissions", "rbac", "compliance", "gdpr",
                            "pci", "audit", "encryption")
                    || SECURITY_HINTS.matcher(text()).find();
        }

        public boolean hasUiComplexity() {
            return labelMatches("ui", "ux", "frontend", "front-end", "design",
                            "responsive", "accessibility", "a11y", "layout")
                    || UI_HINTS.matcher(text()).find();
        }

        /** True when any label equals or contains one of these, case-insensitively. */
        private boolean labelMatches(String... candidates) {
            if (labels == null || labels.isEmpty()) {
                return false;
            }
            for (String label : labels) {
                if (label == null) continue;
                String normalised = label.toLowerCase(java.util.Locale.ROOT).trim();
                for (String candidate : candidates) {
                    if (normalised.equals(candidate) || normalised.contains(candidate)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Title, description and criteria as one string — what the keyword pass reads. */
        private String text() {
            return (title == null ? "" : title) + " "
                    + (description == null ? "" : description) + " "
                    + (acceptanceCriteria == null ? "" : acceptanceCriteria);
        }

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
    /**
     * @param rawPoints the model's number before Fibonacci snapping, or null when the
     *                  model does not snap. Stored so a suggestion reads the same after
     *                  a reload as it did when it was made.
     */
    void recordSuggestion(UUID storyId, UUID predictionId, BigDecimal points,
                          BigDecimal confidence, BigDecimal lowerBound, BigDecimal upperBound,
                          BigDecimal rawPoints);

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

    /**
     * How well specified a sprint's stories are, measured over their own text.
     *
     * <p>Every field here is something the story-point model already reads for a single
     * story; this is the same measurement taken across a sprint. The requirement-change
     * model asks for exactly these — average story length, acceptance criteria per
     * story, stories with none, and how many hedging words appear — and deriving them
     * once rather than twice is the point. Two models disagreeing because they were
     * shown different readings of the same stories would be a bug nobody would ever
     * find, because both answers would look reasonable.</p>
     *
     * @param storyCount           stories in the sprint
     * @param averageWords         mean length of title, description and criteria together
     * @param averageCriteria      mean parsed acceptance criteria per story
     * @param storiesWithoutCriteria stories with no acceptance criteria at all
     * @param ambiguityMarkers     occurrences of "should", "maybe", "TBD", "?" and the
     *                             like — the words that make a requirement negotiable
     */
    record SprintStoryMetrics(long storyCount,
                              BigDecimal averageWords,
                              BigDecimal averageCriteria,
                              long storiesWithoutCriteria,
                              long ambiguityMarkers) {

        /** A sprint with nothing in it, so callers never branch on null. */
        public static SprintStoryMetrics empty() {
            return new SprintStoryMetrics(0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }
    }

    SprintStoryMetrics storyMetricsForSprint(UUID sprintId);

    /**
     * A story whose tasks are estimated at more time than its points allow.
     *
     * @param overrunMinutes how far past the budget the breakdown runs
     * @param budgetMinutes  what the story's points were worth, for context in the reason
     */
    record StoryBudgetBreach(UUID storyId, String itemKey,
                             long overrunMinutes, long budgetMinutes) {}

    /**
     * Stories in this sprint whose task breakdown has outgrown their estimate.
     *
     * <p>Only sized stories can breach: a story nobody has pointed has no budget to
     * exceed, and charging risk for unestimated work would penalise the sprint for
     * planning that has not happened yet rather than for planning that went wrong.</p>
     */
    List<StoryBudgetBreach> budgetBreachesForSprint(UUID sprintId);
}
