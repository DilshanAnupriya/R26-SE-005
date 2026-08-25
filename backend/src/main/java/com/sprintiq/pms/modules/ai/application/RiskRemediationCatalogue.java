package com.sprintiq.pms.modules.ai.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What to actually do about each thing the models complain about.
 *
 * <p>A driver list says "committed 130% of velocity" and stops there, which leaves the
 * reader to invent the remedy. This maps each driver a team can act on to the two or
 * three concrete moves that address it, so the explanation and the fix arrive together.</p>
 *
 * <p>Keyed by the driver's machine name, which both model clients now emit — the mock
 * heuristic and the CatBoost bundles were trained on different feature vocabularies, so
 * a driver has one entry per name it can arrive under. Same steps, two keys, rather than
 * a translation layer that would have to be right about every synonym.</p>
 *
 * <p><strong>Deliberately incomplete.</strong> Several of the strongest drivers —
 * team size, project domain, the baseline velocity, how senior the team is — are facts
 * about the sprint that no checklist changes between now and Friday. Those have no
 * entry, and the panel says so rather than offering busywork that would not move the
 * number. A remediation list that has an answer for everything is one nobody believes
 * the second time they read it.</p>
 */
final class RiskRemediationCatalogue {

    private RiskRemediationCatalogue() {}

    /**
     * One move, and what counts as having made it.
     *
     * @param key    stable slug, unique within the driver; part of the ledger's source key
     * @param title  the action, as an instruction
     * @param detail what "done" means here, and why the model reads it that way
     * @param weight this step's share of the driver's relief; the steps under one driver
     *               sum to 1
     */
    record Step(String key, String title, String detail, double weight) {}

    /** Steps for one driver, or an empty list where nothing in a sprint changes it. */
    static List<Step> forFeature(String feature) {
        return feature == null ? List.of() : STEPS.getOrDefault(feature, List.of());
    }

    // ------------------------------------------------------------------ the moves

    private static final List<Step> COMMITMENT = List.of(
            new Step("cut-commitment",
                    "Move the lowest-value committed stories back to the backlog",
                    "Bring the commitment down to at or under what the team actually delivered "
                    + "over the last three sprints. Work moved out today still gets delivered "
                    + "next sprint; work left in and unfinished gets delivered twice.",
                    0.6),
            new Step("resize-stale",
                    "Re-estimate the committed stories nobody has opened yet",
                    "A size agreed before the sprint's scope settled is the commonest reason "
                    + "this reading is wrong — in either direction. Re-size the ones the team "
                    + "is least sure of.",
                    0.4));

    private static final List<Step> BURN = List.of(
            new Step("rebalance-effort",
                    "Move the remaining work to whoever still has budget",
                    "Effort is going out faster than the sprint is going by. Rebalancing while "
                    + "there is sprint left is the difference between a correction and a "
                    + "post-mortem.",
                    0.5),
            new Step("drop-one-story",
                    "Drop one committed story, and say which one",
                    "Cutting scope is the only lever that reliably closes a burn gap. Naming "
                    + "the story is what makes it a decision the retrospective can find rather "
                    + "than a story that quietly did not get done.",
                    0.5));

    private static final List<Step> SCOPE = List.of(
            new Step("freeze-scope",
                    "Freeze scope for the rest of the sprint",
                    "Agree with the requester that anything new becomes a backlog item for next "
                    + "sprint rather than an edit to this one. The work still happens; it stops "
                    + "happening against a budget that was set without it.",
                    0.6),
            new Step("one-scope-owner",
                    "Give scope decisions a single named owner until the sprint closes",
                    "Churn arriving through several routes is what makes it unpredictable. One "
                    + "approver makes each request visible, and refusable.",
                    0.4));

    private static final List<Step> BLOCKED = List.of(
            new Step("clear-blockers",
                    "Give every blocked task an owner and a date today, or take it out",
                    "A blocker with neither is the one that spends the sprint. If nobody can "
                    + "name who unblocks it and by when, it is not blocked — it is deferred, and "
                    + "the board should say so.",
                    0.7),
            new Step("blockers-first",
                    "Put blockers first at standup for the rest of the sprint",
                    "Blocked work that only surfaces at the review has already cost what it was "
                    + "going to cost.",
                    0.3));

    private static final List<Step> OVERTIME = List.of(
            new Step("stop-overtime",
                    "Stop covering the gap with overtime and cut scope instead",
                    "Overtime already logged is effort spent against an estimate that never "
                    + "included it. More of it does not recover the budget; it just moves where "
                    + "the overrun shows up.",
                    1.0));

    private static final List<Step> SLIPPAGE = List.of(
            new Step("re-estimate-late",
                    "Re-estimate every task already past its estimate",
                    "An estimate the work has overtaken is not a plan any more. A corrected one "
                    + "tells you today whether the sprint still closes.",
                    0.5),
            new Step("start-untouched",
                    "Assign or drop the tasks nobody has started",
                    "An untouched task past half its estimate does not land on time. Give it an "
                    + "owner this morning or take it out of the sprint.",
                    0.5));

    private static final List<Step> REWORK = List.of(
            new Step("root-cause-reopens",
                    "Find the one reason the reopened work came back",
                    "Reopens are work paid for once and done twice. Across a sprint they "
                    + "usually share a single cause — a missing criterion, an untested path, an "
                    + "owner who was never told.",
                    0.5),
            new Step("tighten-done",
                    "Agree what Done means before anything else moves to review",
                    "Most reopens are a definition-of-done disagreement discovered at the worst "
                    + "possible moment.",
                    0.5));

    private static final List<Step> CARRY_OVER = List.of(
            new Step("plan-for-carryover",
                    "Subtract the carried-in work from this sprint's capacity",
                    "Work carried in is capacity already spent. Planning as though it were free "
                    + "is exactly how carry-over compounds from one sprint into three.",
                    1.0));

    private static final List<Step> FATIGUE = List.of(
            new Step("rebalance-load",
                    "Find who is carrying more than their share and move work off them",
                    "Fatigue here means sustained overload on the same few people. Spreading "
                    + "the remaining work is the only lever that exists inside one sprint.",
                    1.0));

    private static final List<Step> CHURN = List.of(
            new Step("route-changes",
                    "Route new requests to the backlog rather than into committed stories",
                    "A change that becomes a new item is visible and schedulable. The same "
                    + "change made as an edit is neither, and nobody can tell afterwards what "
                    + "was agreed.",
                    0.6),
            new Step("confirm-criteria",
                    "Confirm acceptance criteria with the requester before day three",
                    "Most churn is a requirement that was never actually agreed, discovered "
                    + "late enough to cost the sprint.",
                    0.4));

    private static final List<Step> UNESTIMATED = List.of(
            new Step("estimate-unsized",
                    "Estimate every committed story that has no size",
                    "An unestimated story is scope whose shape nobody has agreed, which is "
                    + "where mid-sprint changes come from.",
                    1.0));

    private static final List<Step> SPRINT_GOAL = List.of(
            new Step("set-goal",
                    "Write a sprint goal the team can hold changes against",
                    "Without one there is nothing to measure a mid-sprint request against, so "
                    + "every request looks reasonable on its own.",
                    1.0));

    private static final List<Step> ACCEPTANCE_CRITERIA = List.of(
            new Step("write-ac",
                    "Write acceptance criteria for the committed stories that have none",
                    "A story with no criteria cannot be finished — only abandoned, or "
                    + "reinterpreted by whoever picks it up.",
                    1.0));

    private static final List<Step> AMBIGUITY = List.of(
            new Step("remove-hedging",
                    "Rewrite the hedged sentences in the committed stories",
                    "\"Maybe\", \"etc.\" and \"as needed\" are where the requirement is still "
                    + "open. Replace each one with the decision, or with a question for the "
                    + "requester.",
                    1.0));

    private static final List<Step> LONG_STORIES = List.of(
            new Step("split-long-stories",
                    "Split the longest committed stories",
                    "A story long enough to need summarising usually holds more than one "
                    + "requirement, and only one of them ends up agreed.",
                    1.0));

    private static final List<Step> STAKEHOLDERS = List.of(
            new Step("book-requirement-review",
                    "Book one requirement review with the requester this sprint",
                    "The model reads meetings held as the requirement being actively agreed "
                    + "rather than assumed. So does everyone else.",
                    1.0));

    private static final List<Step> REPLIES = List.of(
            new Step("answer-oldest",
                    "Answer the oldest waiting comments first",
                    "The average is dragged by a few very old threads far more than by "
                    + "everyday delay, so the oldest ones are where the reading actually moves.",
                    0.5),
            new Step("reply-norm",
                    "Agree a reply norm and post it where the team works",
                    "Task comments acknowledged within four working hours — even when the "
                    + "answer is \"not today\".",
                    0.5));

    private static final List<Step> UNANSWERED = List.of(
            new Step("answer-hanging",
                    "Reply to every thread that has been waiting more than a day",
                    "An unanswered question on a task is usually somebody blocked who has not "
                    + "said so yet.",
                    1.0));

    private static final List<Step> DISCUSSION = List.of(
            new Step("decisions-on-task",
                    "Move decisions out of direct messages and onto the task",
                    "A decision made in a DM is invisible to everyone who needs it later — and "
                    + "to this model, which is the smaller problem of the two.",
                    1.0));

    private static final List<Step> SILENCE = List.of(
            new Step("daily-board",
                    "Update the board every day, including the days nothing finished",
                    "Silence reads as a stalled sprint because most of the time it is one. A "
                    + "status change or one comment is enough to tell the difference.",
                    1.0));

    // ------------------------------------------------------- driver name → the moves

    /**
     * Both vocabularies, in one map.
     *
     * <p>The mock heuristic and the trained bundles name the same underlying reading
     * differently — {@code blocked_count} against {@code prev_blocked_tasks},
     * {@code commitment_ratio} against {@code committed_story_points}. Listing each name
     * separately keeps the lookup a plain map miss for anything unrecognised, which is
     * how an unmapped driver ends up correctly offering nothing.</p>
     */
    private static final Map<String, List<Step>> STEPS = build();

    private static Map<String, List<Step>> build() {
        Map<String, List<Step>> map = new LinkedHashMap<>();

        // Over-budget
        map.put("commitment_ratio", COMMITMENT);
        map.put("committed_story_points", COMMITMENT);
        map.put("effort_burn_ratio", BURN);
        map.put("planned_hours", BURN);
        map.put("scope_volatility", SCOPE);
        map.put("scope_added", SCOPE);
        map.put("blocked_count", BLOCKED);
        map.put("blockers", BLOCKED);
        map.put("prev_blocked_tasks", BLOCKED);
        map.put("overtime_minutes", OVERTIME);
        map.put("task_slippage_rate", SLIPPAGE);
        map.put("prev_reopened_tasks", REWORK);
        map.put("reopened_tasks", REWORK);
        map.put("reopened_count", REWORK);
        map.put("prev_rework_score", REWORK);
        map.put("prev_carry_over_points", CARRY_OVER);
        map.put("prev_carry_over_rate", CARRY_OVER);
        map.put("fatigue", FATIGUE);

        // Requirement change
        map.put("requirement_churn_rate", CHURN);
        map.put("prev_change_requests_count", CHURN);
        map.put("prev_story_change_ratio", CHURN);
        map.put("prev_requirement_volatility_score", CHURN);
        map.put("prev_acceptance_criteria_changes", CHURN);
        map.put("unestimated_count", UNESTIMATED);
        map.put("has_sprint_goal", SPRINT_GOAL);
        map.put("stories_without_AC", ACCEPTANCE_CRITERIA);
        map.put("avg_acceptance_criteria_per_story", ACCEPTANCE_CRITERIA);
        map.put("ambiguity_marker_count", AMBIGUITY);
        map.put("avg_story_length_words", LONG_STORIES);
        map.put("stakeholder_meetings_held", STAKEHOLDERS);

        // Collaboration
        map.put("avg_response_time", REPLIES);
        map.put("unanswered_threads", UNANSWERED);
        map.put("comments_per_task", DISCUSSION);
        map.put("inactive_days", SILENCE);

        return Map.copyOf(map);
    }
}
