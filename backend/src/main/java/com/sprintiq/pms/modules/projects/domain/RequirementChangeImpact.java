package com.sprintiq.pms.modules.projects.domain;

import com.sprintiq.pms.common.domain.Priority;

import java.math.BigDecimal;

/**
 * Reads the direction of a requirement change out of the edit itself.
 *
 * <p>The register used to ask the person logging a change whether it made delivery harder
 * or easier. That question has a right answer sitting in the two versions of the story —
 * a priority went up or it went down, a description got longer or shorter — and asking it
 * anyway made the risk score a function of what somebody selected in a dropdown rather
 * than of what they actually did. Two people making the same edit could charge the sprint
 * opposite signs.</p>
 *
 * <p>So the direction is derived. It is derived from a deliberately blunt measure — a
 * priority's weight, a count of criteria, a length of text — because the alternative is
 * to claim this can tell a well-written clarification from a badly-written one, and it
 * cannot. What it can do is be consistent, be explainable in one sentence to the person
 * who just made the edit, and never disagree with itself.</p>
 *
 * <p>Where the type already fixes its own sign — scope added is always worse, scope
 * removed always better — nothing here overrides it. That is what
 * {@link RequirementChangeType#isDirectionFixed()} means, and re-deciding it from a text
 * diff would let "scope added" register as an improvement.</p>
 */
public final class RequirementChangeImpact {

    private RequirementChangeImpact() {}

    /**
     * The story's fields, before and after, as far as this needs to know them.
     *
     * <p>Nulls are ordinary: a story may have no description, no criteria, no estimate,
     * and adding one is exactly the kind of change worth reading a direction from.</p>
     */
    public record Snapshot(Priority priority,
                           String description,
                           String acceptanceCriteria,
                           BigDecimal storyPoints) {}

    /**
     * Which way this edit pushed, or null when the edit changed nothing measurable.
     *
     * <p>Null rather than a default, so the caller can refuse an edit that moved nothing
     * instead of charging a sprint risk points for pressing Save on an unaltered form.</p>
     *
     * @param requested what the person said, used only where nothing can be derived
     */
    public static ChangeImpactDirection infer(RequirementChangeType type,
                                              Snapshot before,
                                              Snapshot after,
                                              ChangeImpactDirection requested) {
        if (type.isDirectionFixed()) {
            // SCOPE_ADDED and SCOPE_REMOVED. The name is the direction.
            return type.effectiveDirection(requested);
        }
        return switch (type) {
            case PRIORITY_CHANGED -> byWeight(weightOf(before.priority()), weightOf(after.priority()));
            case ACCEPTANCE_CRITERIA_CHANGED ->
                    byCriteria(before.acceptanceCriteria(), after.acceptanceCriteria());
            case DESCRIPTION_UPDATED -> byLength(before.description(), after.description());
            // Nothing on the story stands for "other", so this one stays the author's call.
            default -> requested == null ? ChangeImpactDirection.INCREASE : requested;
        };
    }

    /**
     * A raised priority is more disruptive mid-sprint, a lowered one less.
     *
     * <p>Not because urgent work is harder, but because re-prioritising inside a sprint
     * displaces something already committed. Dropping a priority hands that capacity
     * back.</p>
     */
    private static ChangeImpactDirection byWeight(int before, int after) {
        return after == before ? null
                : after > before ? ChangeImpactDirection.INCREASE : ChangeImpactDirection.DECREASE;
    }

    /**
     * More acceptance criteria is more to satisfy; fewer is less.
     *
     * <p>Counted first and measured second. A criterion is a thing that has to be true
     * before the story is done, so five of them is a bigger ask than three however they
     * are worded — but a rewrite that keeps the count and doubles the length has almost
     * certainly tightened them, and falling back to length catches that rather than
     * calling it no change.</p>
     */
    private static ChangeImpactDirection byCriteria(String before, String after) {
        int criteriaBefore = countCriteria(before);
        int criteriaAfter = countCriteria(after);
        if (criteriaBefore != criteriaAfter) {
            return byWeight(criteriaBefore, criteriaAfter);
        }
        return byLength(before, after);
    }

    /** Longer means more was specified; shorter means something was cut. */
    private static ChangeImpactDirection byLength(String before, String after) {
        return byWeight(lengthOf(before), lengthOf(after));
    }

    /**
     * Criteria, counted as the lines somebody wrote.
     *
     * <p>Bullets, numbers and Given/When/Then all end up as one line each, which is how
     * they are written into the box and how they are read back out of it. Blank lines are
     * spacing, not criteria.</p>
     */
    public static int countCriteria(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int lines = 0;
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines++;
            }
        }
        return lines;
    }

    /** Whitespace-insensitive, so re-indenting a block is not a requirement change. */
    private static int lengthOf(String text) {
        return text == null ? 0 : text.replaceAll("\\s+", " ").trim().length();
    }

    private static int weightOf(Priority priority) {
        return priority == null ? Priority.MEDIUM.weight() : priority.weight();
    }
}
