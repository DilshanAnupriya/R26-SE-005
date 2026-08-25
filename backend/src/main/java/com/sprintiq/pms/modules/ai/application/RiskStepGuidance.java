package com.sprintiq.pms.modules.ai.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * How to do the thing the model asked for, in words anyone can follow.
 *
 * <p>{@link RiskRecourseSearch} produces an instruction and a price — "reduce blocked
 * items from 4 to 0, worth 18 points" — by re-running the model. That is rigorous and
 * it is not enough: a delivery lead already knows they want fewer blocked items, and the
 * useful part was always the sentence about giving each one an owner and a date.</p>
 *
 * <p><strong>Nothing here decides anything.</strong> It cannot make a step appear, cannot
 * remove one, cannot change what a step is worth, and cannot alter the target the search
 * found. If the model stops rewarding a lever, the step disappears and this text goes
 * with it. It is help text hanging off a computed decision — closer to the hint under a
 * form field than to logic — which is the whole reason it can be hand-written without
 * putting the checklist back in human hands.</p>
 *
 * <p>Written for somebody who has just joined the team. The model's own vocabulary is
 * unavoidable in the instruction above it — that wording comes out of the feature
 * names — so this is where a phrase like "acceptance criteria" gets explained rather
 * than assumed. Two sentences each: what to do, and why it works.</p>
 */
final class RiskStepGuidance {

    private RiskStepGuidance() {}

    /** The plain-English method for a reading, or null where none is written. */
    static String forFeature(String feature) {
        return feature == null ? null : GUIDANCE.get(feature);
    }

    /** Which readings have guidance — used by the test that keeps every lever covered. */
    static Set<String> covered() {
        return GUIDANCE.keySet();
    }

    private static final Map<String, String> GUIDANCE = build();

    private static Map<String, String> build() {
        Map<String, String> map = new LinkedHashMap<>();

        // -------------------------------------------------------------- over-budget
        map.put("committed_story_points",
                "Go down the list, pick the items that matter least, and move them to the "
                + "next sprint. Nothing is lost — that work gets done a little later, instead "
                + "of everything running late together.");

        map.put("commitment_ratio",
                "The team has taken on more than it normally finishes in a sprint. Move the "
                + "least important items out until the list is about the size the team "
                + "actually gets through. Hoping for a faster-than-usual sprint is not a plan.");

        map.put("planned_hours",
                "The plan needs more hours than the team has. Take work out until the hours "
                + "fit the people who are actually available. Counting on overtime to close "
                + "the gap is how the gap gets bigger.");

        map.put("blocked_count",
                "For every stuck item, write down two things: who will unstick it, and by "
                + "which day. If nobody can answer both, it is not stuck — it is simply not "
                + "happening this sprint, so take it off the list and say so.");

        // ------------------------------------------------------- requirement change
        map.put("baseline_story_count",
                "There are too many separate pieces of work in one sprint for the team to "
                + "finish any of them properly. Move some to the next sprint so what is left "
                + "can actually be completed rather than all half-started.");

        map.put("unestimated_count",
                "Some of the committed work has no size against it — nobody has said whether "
                + "it is an hour or a week. Get the team to agree a rough size for each one. "
                + "Work nobody has sized is usually work nobody has thought through.");

        map.put("has_sprint_goal",
                "Write one sentence saying what this sprint is for, and put it where the team "
                + "can see it. Without one, every new request that arrives mid-sprint sounds "
                + "reasonable on its own, and the work quietly grows.");

        map.put("stories_without_AC",
                "Acceptance criteria are the short list of things that must be true before a "
                + "piece of work counts as finished. Some items here do not have one — write "
                + "it, or nobody will be able to agree when the work is done.");

        map.put("avg_acceptance_criteria_per_story",
                "Most items say what to build but not how to tell it is finished. Add a short "
                + "checklist to each — two or three lines is enough — so that \"done\" means "
                + "the same thing to the person building it and the person who asked for it.");

        map.put("ambiguity_marker_count",
                "Words like \"maybe\", \"roughly\" and \"etc.\" are places where somebody has "
                + "not decided yet. Read through the committed items and replace each one with "
                + "an actual decision, or with a question for whoever asked for the work.");

        map.put("avg_story_length_words",
                "Some descriptions are long enough that they are probably describing more than "
                + "one job. Split them, so each item is a single thing one person can pick up "
                + "and finish — long items tend to get partly done and reinterpreted.");

        map.put("stakeholder_meetings_held",
                "Book a short session with whoever asked for this work and walk them through "
                + "what is being built. Most late changes are things that would have come up "
                + "in that conversation, when they were still cheap to make.");

        // ------------------------------------------------------------ collaboration
        map.put("avg_response_time",
                "Questions on tasks are sitting a long time before anyone answers. Start with "
                + "the oldest ones rather than the newest — a handful of very old questions "
                + "drags this number far more than everyday delay does.");

        map.put("comments_per_task",
                "Decisions are being made somewhere other than on the work itself — in chat, "
                + "or in a corridor. Write them on the task instead. Anyone who joins later, "
                + "or picks the work up next week, then does not have to go and ask.");

        map.put("blockers",
                "For every task the team has called stuck, name the person who will unstick it "
                + "and the day it will happen. Blocked work that only comes up at the end of "
                + "the sprint has already cost whatever it was going to cost.");

        map.put("unanswered_threads",
                "Someone asked a question on a task and nobody replied. That is usually a "
                + "person waiting who has not said out loud that they are waiting. Answer each "
                + "one — even \"not this week\" is an answer they can work with.");

        return Map.copyOf(map);
    }
}
