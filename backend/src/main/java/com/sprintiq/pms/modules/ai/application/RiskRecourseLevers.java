package com.sprintiq.pms.modules.ai.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which model inputs a team can actually change, and in which direction.
 *
 * <p>This is the feature-space metadata that algorithmic recourse requires: a search for
 * "what would lower this score" has to be told which coordinates it is allowed to move,
 * or it proposes shrinking the team and un-spending last month's overtime. Every
 * published recourse method carries an equivalent — Ustun's actionability sets, DiCE's
 * {@code features_to_vary} — because without it a counterfactual is arithmetic rather
 * than advice.</p>
 *
 * <p>What it deliberately does <em>not</em> hold is the remedy. There is no sentence here
 * telling anyone to split a story or clear a blocker; there is only the statement that
 * committed points are reducible and team size is not. The action, the target value and
 * what it is worth are all measured by re-running the model in
 * {@link RiskRecourseSearch}.</p>
 *
 * <p><strong>The exclusions carry as much meaning as the entries.</strong> Effort already
 * burned, overtime already logged, scope churn already recorded, and every {@code prev_*}
 * reading are all real drivers that a sprint cannot move: you cannot un-spend an hour.
 * {@code task_slippage_rate} is left out for a different reason — re-estimating the late
 * tasks would move it, but that changes the measurement rather than the sprint, and a
 * recourse system that proposes it is teaching people to game the model.</p>
 */
final class RiskRecourseLevers {

    private RiskRecourseLevers() {}

    /** Which way the sprint has to move a reading for the model to read it as better. */
    enum Action {
        /** Lower is safer: a commitment, a backlog of blocked items, a reply time. */
        REDUCE,
        /** Higher is safer: discussion, acceptance criteria, reviews held. */
        RAISE,
        /** A flag that ought to be set: the sprint has a goal, or it does not. */
        ENABLE
    }

    /**
     * One movable coordinate of the feature space.
     *
     * @param feature machine name, as the model emits it
     * @param noun    what the reading <em>is</em>, as a noun phrase, so the generated
     *                sentence reads as English. Names the feature; it does not advise.
     * @param action  the admissible direction of travel
     * @param bound   how far one sprint can plausibly move it — for {@code REDUCE} the
     *                lowest reachable fraction of the current value, for {@code RAISE}
     *                the highest reachable multiple. Unused for {@code ENABLE}.
     * @param unit    appended to the numbers in the sentence, or blank. {@code "%"} also
     *                changes how they are written: a reading the model holds as a ratio
     *                is stated as a percentage, because "cut the commitment from 135% to
     *                88% of velocity" is an instruction and "from 1.35 to 0.88" is a
     *                puzzle
     */
    record Lever(String feature, String noun, Action action, double bound, String unit) {}

    static Lever forFeature(String feature) {
        return feature == null ? null : LEVERS.get(feature);
    }

    static List<Lever> all() {
        return List.copyOf(LEVERS.values());
    }

    /**
     * Both feature vocabularies, in one map.
     *
     * <p>The mock heuristic and the trained bundles name overlapping readings
     * differently, and the two are not always the same reading under two names —
     * {@code blocked_count} is this sprint's blocked items and is movable, while
     * {@code prev_blocked_tasks} is last sprint's and is history. Listing each name on
     * its own is what keeps that distinction, and a plain map miss is how an unlisted
     * feature correctly yields no step.</p>
     */
    private static final Map<String, Lever> LEVERS = build();

    private static Map<String, Lever> build() {
        Map<String, Lever> map = new LinkedHashMap<>();

        // -------------------------------------------------------------- over-budget
        put(map, new Lever("committed_story_points", "the commitment",
                Action.REDUCE, 0.65, "points"));
        put(map, new Lever("commitment_ratio", "the commitment against recent velocity",
                Action.REDUCE, 0.65, "%"));
        put(map, new Lever("planned_hours", "the planned hours",
                Action.REDUCE, 0.7, "hours"));
        put(map, new Lever("blocked_count", "blocked items", Action.REDUCE, 0.0, ""));

        // ------------------------------------------------------- requirement change
        put(map, new Lever("baseline_story_count", "the committed stories",
                Action.REDUCE, 0.7, ""));
        put(map, new Lever("unestimated_count", "unestimated committed items",
                Action.REDUCE, 0.0, ""));
        put(map, new Lever("has_sprint_goal", "a sprint goal", Action.ENABLE, 0, ""));
        put(map, new Lever("stories_without_AC", "committed stories with no acceptance criteria",
                Action.REDUCE, 0.0, ""));
        put(map, new Lever("avg_acceptance_criteria_per_story", "acceptance criteria per story",
                Action.RAISE, 2.0, ""));
        put(map, new Lever("ambiguity_marker_count", "hedging words in the committed stories",
                Action.REDUCE, 0.0, ""));
        put(map, new Lever("avg_story_length_words", "the average story length",
                Action.REDUCE, 0.6, "words"));
        put(map, new Lever("stakeholder_meetings_held", "stakeholder reviews held",
                Action.RAISE, 3.0, ""));

        // ------------------------------------------------------------ collaboration
        put(map, new Lever("avg_response_time", "the average reply time",
                Action.REDUCE, 0.25, "hours"));
        put(map, new Lever("comments_per_task", "written discussion per task",
                Action.RAISE, 3.0, "comments"));
        put(map, new Lever("blockers", "blocked tasks", Action.REDUCE, 0.0, ""));
        put(map, new Lever("unanswered_threads", "threads still waiting for a reply",
                Action.REDUCE, 0.0, ""));

        return Map.copyOf(map);
    }

    private static void put(Map<String, Lever> map, Lever lever) {
        map.put(lever.feature(), lever);
    }
}
