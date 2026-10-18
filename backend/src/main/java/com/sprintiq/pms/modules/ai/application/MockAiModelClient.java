package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.PredictionDriver;
import com.sprintiq.pms.modules.ai.domain.PredictionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, feature-driven stand-in for the model services.
 *
 * <p>It is not a stub returning constants. Every number here is derived from the same
 * features a real model would receive, using published estimation heuristics, so the
 * product can be demonstrated, tested and reviewed end to end: a story with four
 * acceptance criteria and two dependencies really does come back bigger than a
 * one-liner, and adding scope to a running sprint really does move its risk score. The
 * UI, the explanations, the Accept/Override loop and the feedback table are all
 * exercised for real — only the arithmetic is a placeholder.</p>
 *
 * <p>Deterministic on purpose. A mock that jitters makes every snapshot test flaky and
 * every demo unreproducible; the same features must always produce the same answer, as
 * they would from a trained model with fixed weights.</p>
 *
 * <p>Confidence is reported honestly. Where the heuristic is working from thin evidence
 * — no comparable stories, no sprint history — it says so with a low confidence and a
 * wide interval rather than manufacturing false precision.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiModelClient implements AiModelClient {

    /**
     * The estimation scale teams actually use; predictions snap to it.
     *
     * <p>The Planning Poker deck, 0 to 100 — the one scale SprintIQ uses for the picker, the
     * model service and this, so the mock and the real model answer on the same cards.</p>
     */
    private static final BigDecimal[] SCALE = {
            BigDecimal.ZERO, new BigDecimal("0.5"), BigDecimal.ONE, BigDecimal.valueOf(2),
            BigDecimal.valueOf(3), BigDecimal.valueOf(5), BigDecimal.valueOf(8),
            BigDecimal.valueOf(13), BigDecimal.valueOf(20), BigDecimal.valueOf(40),
            BigDecimal.valueOf(100)};

    @Override
    public String providerName() {
        return "mock-heuristic";
    }

    @Override
    public InferenceResult infer(InferenceRequest request) {
        FeatureVector features = request.features();
        return switch (request.modelCode()) {
            case M1_STORY_POINTS -> storyPoints(features);
            case M1_VELOCITY -> velocity(features);
            case M2_OVER_BUDGET -> overBudgetRisk(features);
            case M2_REQUIREMENT_CHANGE -> requirementChangeRisk(features);
            case M2_COMMUNICATION_COLLABORATION -> collaborationRisk(features);
            case M3_ALLOCATION -> allocationFit(features);
            case M4_COST_INITIAL -> cost(features, false);
            case M4_COST_MID -> cost(features, true);
        };
    }

    // ------------------------------------------------------------------ M1 · size

    /**
     * Size from scope signals, anchored to the project's own scale.
     *
     * <p>The anchor matters more than the arithmetic. "Five points" means nothing in the
     * abstract — it means something relative to what this team has called five before —
     * so the estimate starts from the project's median and moves from there rather than
     * starting from an absolute complexity score.</p>
     */
    private InferenceResult storyPoints(FeatureVector features) {
        BigDecimal anchor = anchor(features);
        List<PredictionDriver> drivers = new ArrayList<>();

        double multiplier = 1.0;

        int criteria = features.integer("acceptance_criteria_count");
        if (criteria > 0) {
            double effect = 1 + Math.min(criteria - 2, 6) * 0.12;
            multiplier *= Math.max(effect, 0.7);
            drivers.add(driver("acceptance_criteria_count", "Acceptance criteria",
                    anchor.multiply(BigDecimal.valueOf(effect - 1)),
                    "%d criteria to satisfy".formatted(criteria)));
        }

        int words = features.integer("word_count");
        if (words > 0) {
            // Description length correlates with scope up to a point, then stops meaning
            // anything — a 900-word story is not three times a 300-word one, it is a story
            // that needs splitting. The log keeps the curve from running away.
            double effect = Math.log10(Math.max(words, 10) / 40.0) * 0.22;
            multiplier *= 1 + effect;
            drivers.add(driver("word_count", "Specification length",
                    anchor.multiply(BigDecimal.valueOf(effect)),
                    "%d words across title, description and criteria".formatted(words)));
        }

        int dependencies = features.integer("dependency_count");
        if (dependencies > 0) {
            double effect = dependencies * 0.15;
            multiplier *= 1 + effect;
            drivers.add(driver("dependency_count", "Blocked by other work",
                    anchor.multiply(BigDecimal.valueOf(effect)),
                    "%d dependency edge%s".formatted(dependencies, dependencies == 1 ? "" : "s")));
        }

        int tasks = features.integer("task_count");
        if (tasks > 0) {
            double effect = Math.min(tasks, 8) * 0.08;
            multiplier *= 1 + effect;
            drivers.add(driver("task_count", "Broken-down tasks",
                    anchor.multiply(BigDecimal.valueOf(effect)),
                    "%d child task%s".formatted(tasks, tasks == 1 ? "" : "s")));
        }

        String type = features.text("story_type");
        if ("SPIKE".equals(type)) {
            // A spike's size is bounded by its timebox, not by its unknowns.
            multiplier *= 0.6;
            drivers.add(driver("story_type", "Spike", anchor.multiply(BigDecimal.valueOf(-0.4)),
                    "Research work is timeboxed rather than sized"));
        } else if ("BUG".equals(type)) {
            multiplier *= 0.75;
            drivers.add(driver("story_type", "Defect", anchor.multiply(BigDecimal.valueOf(-0.25)),
                    "Defects are usually smaller than the feature that caused them"));
        }

        if (!features.flag("has_description")) {
            // An unspecified story is not small; it is unknown. Push up and widen.
            multiplier *= 1.2;
            drivers.add(driver("has_description", "No description",
                    anchor.multiply(BigDecimal.valueOf(0.2)),
                    "Unspecified work is estimated conservatively"));
        }

        BigDecimal raw = anchor.multiply(BigDecimal.valueOf(multiplier));
        BigDecimal points = snapToScale(raw);

        int comparables = features.integer("comparable_count");
        BigDecimal confidence = confidenceFrom(comparables, features.flag("has_description"), criteria);
        BigDecimal spread = BigDecimal.ONE.subtract(confidence).max(BigDecimal.valueOf(0.15));

        BigDecimal lower = snapToScale(raw.multiply(BigDecimal.ONE.subtract(spread)));
        BigDecimal upper = snapToScale(raw.multiply(BigDecimal.ONE.add(spread)));

        drivers.sort((left, right) -> right.contribution().abs().compareTo(left.contribution().abs()));
        String explanation = ("Estimated %s points against a project baseline of %s. %s"
                .formatted(points.toPlainString(), anchor.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                        comparables > 0
                                ? "Drawn from %d comparable stories.".formatted(comparables)
                                : "No comparable stories yet, so treat this as a starting point for discussion."))
                .trim();

        return new InferenceResult(points, null, confidence, lower, upper, List.copyOf(drivers),
                explanation, Map.of("heuristic", "scope-multiplier", "multiplier", multiplier),
                PredictionStatus.SUCCESS, null);
    }

    /** Project median, then team average, then the industry-default 3. */
    private BigDecimal anchor(FeatureVector features) {
        BigDecimal comparable = features.decimal("comparable_median_points");
        if (comparable.compareTo(BigDecimal.ZERO) > 0) return comparable;
        BigDecimal median = features.decimal("project_median_points");
        if (median.compareTo(BigDecimal.ZERO) > 0) return median;
        return BigDecimal.valueOf(3);
    }

    private BigDecimal confidenceFrom(int comparables, boolean hasDescription, int criteria) {
        double confidence = 0.35;
        confidence += Math.min(comparables, 20) * 0.015;   // up to +0.30 from history
        if (hasDescription) confidence += 0.12;
        if (criteria > 0) confidence += 0.10;
        return BigDecimal.valueOf(Math.min(confidence, 0.92)).setScale(3, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------- M1 · velocity

    /**
     * Next-sprint velocity as a recency-weighted mean of history, adjusted for the
     * capacity actually available.
     *
     * <p>Weighted rather than flat because a team's composition changes: six sprints ago
     * describes a different team. Adjusted for capacity because predicting last
     * quarter's velocity for a sprint where half the team is on leave is not a
     * prediction, it is a formality.</p>
     */
    private InferenceResult velocity(FeatureVector features) {
        List<Object> history = features.list("historical_velocities");
        List<PredictionDriver> drivers = new ArrayList<>();

        BigDecimal baseline;
        if (history.isEmpty()) {
            baseline = features.decimal("capacity_points", BigDecimal.valueOf(20));
            drivers.add(driver("capacity_points", "Planned capacity", baseline,
                    "No completed sprints yet — capacity is the only signal available"));
        } else {
            double weightedSum = 0;
            double weightTotal = 0;
            for (int index = 0; index < history.size(); index++) {
                double weight = index + 1.0;   // oldest first, so later entries weigh more
                weightedSum += toDouble(history.get(index)) * weight;
                weightTotal += weight;
            }
            baseline = BigDecimal.valueOf(weightedSum / weightTotal).setScale(2, RoundingMode.HALF_UP);
            drivers.add(driver("historical_velocities", "Recent delivery", baseline,
                    "Weighted mean of the last %d sprints".formatted(history.size())));
        }

        double adjustment = 1.0;

        BigDecimal absence = features.decimal("absence_days");
        BigDecimal teamSize = features.decimal("team_size", BigDecimal.ONE).max(BigDecimal.ONE);
        BigDecimal duration = features.decimal("duration_days", BigDecimal.TEN).max(BigDecimal.ONE);
        if (absence.compareTo(BigDecimal.ZERO) > 0) {
            double lost = absence.doubleValue() / (teamSize.doubleValue() * duration.doubleValue());
            adjustment -= Math.min(lost, 0.5);
            drivers.add(driver("absence_days", "Planned absence",
                    baseline.multiply(BigDecimal.valueOf(-Math.min(lost, 0.5))),
                    "%s person-days unavailable".formatted(absence.toPlainString())));
        }

        BigDecimal unestimated = features.decimal("unestimated_count");
        if (unestimated.compareTo(BigDecimal.ZERO) > 0) {
            adjustment -= Math.min(unestimated.doubleValue() * 0.03, 0.15);
            drivers.add(driver("unestimated_count", "Unestimated items",
                    baseline.multiply(BigDecimal.valueOf(-0.03 * unestimated.doubleValue())),
                    "%s items carry no estimate".formatted(unestimated.toPlainString())));
        }

        BigDecimal predicted = baseline.multiply(BigDecimal.valueOf(Math.max(adjustment, 0.4)))
                .setScale(1, RoundingMode.HALF_UP);

        // Confidence tracks how consistent the history is, not how much of it there is:
        // ten wildly varying sprints justify less certainty than three steady ones.
        double variability = variability(history);
        BigDecimal confidence = BigDecimal.valueOf(
                        Math.min(0.90, Math.max(0.30, 0.85 - variability))).setScale(3, RoundingMode.HALF_UP);
        BigDecimal margin = predicted.multiply(BigDecimal.ONE.subtract(confidence))
                .setScale(1, RoundingMode.HALF_UP);

        drivers.sort((left, right) -> right.contribution().abs().compareTo(left.contribution().abs()));
        return new InferenceResult(predicted, null, confidence,
                predicted.subtract(margin).max(BigDecimal.ZERO), predicted.add(margin),
                List.copyOf(drivers),
                "Expect around %s points, based on %s.".formatted(predicted.toPlainString(),
                        history.isEmpty() ? "planned capacity" : "recent delivery adjusted for availability"),
                Map.of("heuristic", "weighted-history", "variability", variability),
                PredictionStatus.SUCCESS, null);
    }

    // ------------------------------------------------------------------ M2 · risk

    /**
     * Probability that the sprint overruns its effort budget.
     *
     * <p>Built from independent stressors — over-commitment against velocity, effort
     * burned ahead of schedule, scope added after the freeze, work stuck, overtime,
     * tasks past their estimate and work being redone — combined through a logistic so
     * that no single factor can pin the score at certainty on its own.</p>
     */
    private InferenceResult overBudgetRisk(FeatureVector features) {
        List<PredictionDriver> drivers = new ArrayList<>();
        double logit = -1.6;   // sprints are not over budget by default

        double commitmentRatio = features.number("commitment_ratio");
        if (commitmentRatio > 1.0) {
            double contribution = Math.min((commitmentRatio - 1.0) * 2.4, 2.2);
            logit += contribution;
            drivers.add(driver("commitment_ratio", "Commitment vs velocity",
                    BigDecimal.valueOf(contribution),
                    "Committed %.0f%% of what the team usually delivers".formatted(commitmentRatio * 100)));
        }

        double burnRatio = features.number("effort_burn_ratio");
        double elapsed = features.number("elapsed_fraction");
        if (elapsed > 0.15 && burnRatio > elapsed) {
            double contribution = Math.min((burnRatio - elapsed) * 3.0, 1.8);
            logit += contribution;
            drivers.add(driver("effort_burn_ratio", "Effort ahead of schedule",
                    BigDecimal.valueOf(contribution),
                    "%.0f%% of the budget spent %.0f%% of the way through".formatted(
                            burnRatio * 100, elapsed * 100)));
        }

        double volatility = features.number("scope_volatility");
        if (volatility > 0.05) {
            double contribution = Math.min(volatility * 2.0, 1.5);
            logit += contribution;
            drivers.add(driver("scope_volatility", "Scope churn", BigDecimal.valueOf(contribution),
                    "%.0f%% of the commitment has changed since the freeze".formatted(volatility * 100)));
        }

        // Stories and tasks, because that is what blocked_count now counts. A team that
        // works on the board blocks tasks, and reading only the story figure was why
        // "Assess" used to hand back the pre-block score and undo the ledger's charge.
        double blockable = features.number("blockable_count");
        double blockedRatio = ratio(features.number("blocked_count"),
                blockable > 0 ? blockable : features.number("story_count"));
        if (blockedRatio > 0) {
            double contribution = Math.min(blockedRatio * 2.2, 1.2);
            logit += contribution;
            drivers.add(driver("blocked_count", "Blocked work", BigDecimal.valueOf(contribution),
                    "%.0f%% of items are blocked".formatted(blockedRatio * 100)));
        }

        double overtimeRatio = ratio(features.number("overtime_minutes"), features.number("effort_minutes"));
        if (overtimeRatio > 0.05) {
            double contribution = Math.min(overtimeRatio * 2.5, 1.4);
            logit += contribution;
            drivers.add(driver("overtime_minutes", "Overtime already logged",
                    BigDecimal.valueOf(contribution),
                    "%.0f%% of logged effort is overtime".formatted(overtimeRatio * 100)));
        }

        // Tasks already behind their own estimate. The strongest of these signals,
        // because unlike everything above it this one is not a ratio of plans — the
        // work is measurably late and no amount of replanning makes it less so.
        double slippageRate = features.number("task_slippage_rate");
        if (slippageRate > 0) {
            double contribution = Math.min(slippageRate * 3.0, 2.0);
            logit += contribution;
            drivers.add(driver("task_slippage_rate", "Tasks behind their estimate",
                    BigDecimal.valueOf(contribution),
                    "%.0f tasks past their estimate, %.0f not started past half-time".formatted(
                            features.number("tasks_over_estimate"),
                            features.number("tasks_not_started_past_half"))));
        }

        // Rework. Every reopen is work being done a second time against an estimate that
        // was only ever paid for once, which is the plainest way a sprint goes over
        // budget — and the only stressor here that a team can watch accumulate in real
        // time on the board.
        //
        // This term is why the score survives a re-assessment. Without it the heuristic
        // was blind to rework, so pressing Assess re-scored on six stressors that had not
        // moved, handed back the number it started with, and discarded the reopen ledger
        // that had been correctly raising it. The trained CatBoost bundle reads the same
        // two columns, so both models now answer the same question the same way.
        double reopens = features.number("prev_reopened_tasks");
        if (reopens > 0) {
            double contribution = Math.min(reopens * 0.22, 1.8);
            logit += contribution;
            drivers.add(driver("prev_reopened_tasks", "Work being redone",
                    BigDecimal.valueOf(contribution),
                    "%.0f reopen%s across this sprint's work".formatted(
                            reopens, reopens == 1 ? "" : "s")));
        }

        List<String> mitigations = new ArrayList<>(List.of(
                "Split or defer the lowest-value committed stories",
                "Freeze scope for the remainder of the sprint",
                "Clear the blockers before adding anything new"));
        if (features.number("tasks_not_started_past_half") > 0) {
            mitigations.add("Re-estimate or reassign the tasks nobody has opened yet — "
                    + "an untouched task past half its estimate will not land on time");
        }

        return classification(logit, drivers, features, mitigations, "over-budget");
    }

    /** Probability of significant requirement churn during the sprint. */
    private InferenceResult requirementChangeRisk(FeatureVector features) {
        List<PredictionDriver> drivers = new ArrayList<>();
        double logit = -1.9;

        double churnRate = features.number("requirement_churn_rate");
        if (churnRate > 0) {
            double contribution = Math.min(churnRate * 3.2, 2.4);
            logit += contribution;
            drivers.add(driver("requirement_churn_rate", "Historical requirement churn",
                    BigDecimal.valueOf(contribution),
                    "%.2f post-approval changes per requirement".formatted(churnRate)));
        }

        double volatility = features.number("scope_volatility");
        if (volatility > 0.02) {
            double contribution = Math.min(volatility * 2.6, 1.6);
            logit += contribution;
            drivers.add(driver("scope_volatility", "Scope already moving",
                    BigDecimal.valueOf(contribution),
                    "%.0f%% churn against the commitment".formatted(volatility * 100)));
        }

        double unestimatedRatio = ratio(features.number("unestimated_count"), features.number("story_count"));
        if (unestimatedRatio > 0.1) {
            double contribution = Math.min(unestimatedRatio * 1.8, 1.1);
            logit += contribution;
            drivers.add(driver("unestimated_count", "Unestimated scope",
                    BigDecimal.valueOf(contribution),
                    "%.0f%% of committed items are unestimated".formatted(unestimatedRatio * 100)));
        }

        if (!features.flag("has_sprint_goal")) {
            logit += 0.6;
            drivers.add(driver("has_sprint_goal", "No sprint goal", BigDecimal.valueOf(0.6),
                    "A sprint without a stated goal has nothing to hold scope against"));
        }

        double reopenedRatio = ratio(features.number("reopened_count"), features.number("story_count"));
        if (reopenedRatio > 0) {
            double contribution = Math.min(reopenedRatio * 2.0, 1.2);
            logit += contribution;
            drivers.add(driver("reopened_count", "Reopened work", BigDecimal.valueOf(contribution),
                    "Items coming back suggest the requirement was not settled"));
        }

        return classification(logit, drivers, features,
                List.of("Confirm acceptance criteria with the requester before day three",
                        "Convert late changes into new backlog items rather than edits",
                        "Agree a sprint goal the team can measure changes against"),
                "requirement-change");
    }

    /**
     * Collaboration risk from the same five readings the real model scores.
     *
     * <p>The thresholds are the service's composite bands, converted to logit
     * contributions rather than reinvented: the mock exists so the product can be
     * demonstrated and tested without the model service, and a mock with its own
     * opinion about what counts as a slow reply would make every test written against
     * it a test of the wrong boundary.</p>
     */
    private InferenceResult collaborationRisk(FeatureVector features) {
        List<PredictionDriver> drivers = new ArrayList<>();
        double logit = -2.2;

        // Null means no thread had two participants, which the service leaves absent
        // rather than sending as zero. Absent is not fast — it is unmeasurable, so
        // nothing is added either way.
        BigDecimal replyHours = features.decimal("avg_response_time", null);
        if (replyHours != null) {
            double hours = replyHours.doubleValue();
            double contribution = hours > 48 ? 1.8 : hours > 24 ? 0.9 : hours > 12 ? 0.4 : 0.0;
            if (contribution > 0) {
                logit += contribution;
                drivers.add(driver("avg_response_time", "Slow replies",
                        BigDecimal.valueOf(contribution),
                        "%.0fh between a comment and an answer".formatted(hours)));
            }
        }

        double commentsPerTask = features.number("comments_per_task");
        double taskCount = features.number("collaboration_task_count");
        if (taskCount > 0) {
            double contribution = commentsPerTask < 1 ? 1.4
                    : commentsPerTask < 2 ? 0.9 : commentsPerTask < 3 ? 0.4 : 0.0;
            if (contribution > 0) {
                logit += contribution;
                drivers.add(driver("comments_per_task", "Little written discussion",
                        BigDecimal.valueOf(contribution),
                        "%.1f comments per task across %.0f tasks".formatted(commentsPerTask, taskCount)));
            }
        }

        double inactiveDays = features.number("inactive_days");
        double contribution = inactiveDays > 7 ? 1.8 : inactiveDays > 4 ? 0.9 : inactiveDays > 2 ? 0.4 : 0.0;
        if (contribution > 0) {
            logit += contribution;
            double longest = features.number("longest_silence_days");
            drivers.add(driver("inactive_days", "Days with nothing recorded",
                    BigDecimal.valueOf(contribution),
                    "%.0f silent days, the longest run being %.0f".formatted(inactiveDays, longest)));
        }

        double blockers = features.number("blockers");
        contribution = blockers > 5 ? 1.8 : blockers > 3 ? 0.9 : blockers > 1 ? 0.4 : 0.0;
        if (contribution > 0) {
            logit += contribution;
            drivers.add(driver("blockers", "Blocked tasks", BigDecimal.valueOf(contribution),
                    "%.0f tasks the team has called stuck".formatted(blockers)));
        }

        double reopened = features.number("reopened_tasks");
        contribution = reopened > 4 ? 1.4 : reopened > 2 ? 0.9 : reopened > 0 ? 0.4 : 0.0;
        if (contribution > 0) {
            logit += contribution;
            drivers.add(driver("reopened_tasks", "Reopened work", BigDecimal.valueOf(contribution),
                    "%.0f tasks came back after being called done".formatted(reopened)));
        }

        double unanswered = features.number("unanswered_threads");
        if (unanswered > 0) {
            contribution = Math.min(unanswered * 0.25, 1.0);
            logit += contribution;
            drivers.add(driver("unanswered_threads", "Questions left hanging",
                    BigDecimal.valueOf(contribution),
                    "%.0f comment thread%s with no reply after a day"
                            .formatted(unanswered, unanswered == 1 ? "" : "s")));
        }

        return classification(logit, drivers, features,
                List.of("Agree a reply norm — task comments acknowledged within four working hours",
                        "Give every blocked task a named owner and review them at standup",
                        "Move decisions onto the task rather than into direct messages"),
                "communication-collaboration");
    }

    private InferenceResult classification(double logit, List<PredictionDriver> drivers,
                                           FeatureVector features, List<String> mitigations,
                                           String heuristic) {
        double probability = 1.0 / (1.0 + Math.exp(-logit));
        BigDecimal score = BigDecimal.valueOf(probability).setScale(3, RoundingMode.HALF_UP);
        boolean positive = probability >= 0.5;

        // Evidence, not probability, sets confidence: a sprint with no history behind it
        // can produce a confident-looking 0.7 that nothing supports.
        double evidence = Math.min(features.number("story_count") / 12.0, 1.0) * 0.4
                + Math.min(features.number("history_depth") / 4.0, 1.0) * 0.4 + 0.2;
        BigDecimal confidence = BigDecimal.valueOf(Math.min(evidence, 0.9))
                .setScale(3, RoundingMode.HALF_UP);

        drivers.sort((left, right) -> right.contribution().compareTo(left.contribution()));
        String top = drivers.isEmpty() ? "no material risk signals" : drivers.get(0).label().toLowerCase();
        String explanation = positive
                ? "Elevated risk at %.0f%%, driven mainly by %s.".formatted(probability * 100, top)
                : "Risk looks contained at %.0f%%; the strongest signal is %s.".formatted(probability * 100, top);

        return new InferenceResult(score, positive ? "AT_RISK" : "ON_TRACK", confidence,
                null, null, List.copyOf(drivers), explanation,
                Map.of("heuristic", heuristic, "logit", logit, "mitigations", mitigations),
                PredictionStatus.SUCCESS, null);
    }

    // ------------------------------------------------------------ M3 · allocation

    /**
     * Fit of one candidate for one item.
     *
     * <p>Called once per candidate; the ranking is assembled by the caller. Skill match
     * and reliability push the score up, current load pushes it down — and load is a
     * separate signal rather than a discount baked into fit, so the UI can say "best
     * match, but already at 120%" instead of silently demoting the right person.</p>
     */
    private InferenceResult allocationFit(FeatureVector features) {
        List<PredictionDriver> drivers = new ArrayList<>();
        double score = 0.45;

        double skillMatch = features.number("skill_match_ratio");
        if (features.integer("required_skill_count") > 0) {
            double contribution = skillMatch * 0.28;
            score += contribution;
            drivers.add(driver("skill_match_ratio", "Skill match", BigDecimal.valueOf(contribution),
                    "%.0f%% of the required skills at working proficiency".formatted(skillMatch * 100)));
        }

        double consistency = features.number("consistency_score");
        double contribution = (consistency - 0.5) * 0.20;
        score += contribution;
        drivers.add(driver("consistency_score", "Delivery consistency", BigDecimal.valueOf(contribution),
                consistency >= 0.5
                        ? "Delivers predictably sprint to sprint"
                        : "Output varies noticeably between sprints"));

        double load = features.number("current_load_pct");
        if (load > 80) {
            double penalty = -Math.min((load - 80) / 100.0, 0.35);
            score += penalty;
            drivers.add(driver("current_load_pct", "Current workload", BigDecimal.valueOf(penalty),
                    "Already at %.0f%% of a sprint's capacity".formatted(load)));
        } else if (load < 50) {
            double bonus = 0.08;
            score += bonus;
            drivers.add(driver("current_load_pct", "Available capacity", BigDecimal.valueOf(bonus),
                    "At %.0f%% load with room to take work".formatted(load)));
        }

        double defectRate = features.number("defect_rate");
        if (defectRate > 0.1) {
            double penalty = -Math.min(defectRate * 0.4, 0.18);
            score += penalty;
            drivers.add(driver("defect_rate", "Defect rate", BigDecimal.valueOf(penalty),
                    "%.0f%% of delivered work came back".formatted(defectRate * 100)));
        }

        double learning = features.number("learning_rate");
        if (learning > 0 && skillMatch < 0.6) {
            // Growth matters most where the fit is imperfect: this is the "stretch
            // assignment" signal, and it is the reason learning rate is a stored metric.
            double bonus = Math.min(learning * 0.15, 0.10);
            score += bonus;
            drivers.add(driver("learning_rate", "Improving quickly", BigDecimal.valueOf(bonus),
                    "Picks up unfamiliar work faster than the team average"));
        }

        double availability = features.number("availability_rate");
        if (availability < 0.9) {
            double penalty = -(0.9 - availability) * 0.3;
            score += penalty;
            drivers.add(driver("availability_rate", "Part-time availability",
                    BigDecimal.valueOf(penalty),
                    "Available %.0f%% of the sprint".formatted(availability * 100)));
        }

        BigDecimal fit = BigDecimal.valueOf(Math.max(0.02, Math.min(score, 0.99)))
                .setScale(3, RoundingMode.HALF_UP);
        BigDecimal predictedPerformance = BigDecimal.valueOf(
                        features.number("avg_velocity") * (0.7 + fit.doubleValue() * 0.6))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal confidence = BigDecimal.valueOf(
                        Math.min(0.88, 0.35 + Math.min(features.number("tasks_completed") / 40.0, 1.0) * 0.5))
                .setScale(3, RoundingMode.HALF_UP);

        drivers.sort((left, right) -> right.contribution().abs().compareTo(left.contribution().abs()));
        return new InferenceResult(fit, null, confidence, null, null, List.copyOf(drivers),
                "Fit %.0f%% — %s.".formatted(fit.doubleValue() * 100,
                        drivers.isEmpty() ? "no distinguishing signals" : drivers.get(0).detail()),
                Map.of("heuristic", "additive-fit", "predicted_performance", predictedPerformance),
                PredictionStatus.SUCCESS, null);
    }

    // ------------------------------------------------------------------ M4 · cost

    /**
     * Sprint cost from capacity, blended rate and — mid-sprint — recorded effort.
     *
     * <p>The two stages differ in what they trust. The initial forecast has only a plan,
     * so it prices planned capacity and admits a wide margin. The mid-sprint forecast
     * has logged hours, so it extrapolates the actual burn and narrows sharply. Both
     * bounds are returned; a point estimate on its own would be read as a commitment.</p>
     */
    private InferenceResult cost(FeatureVector features, boolean midSprint) {
        List<PredictionDriver> drivers = new ArrayList<>();

        BigDecimal rate = features.decimal("blended_hourly_rate", BigDecimal.valueOf(65));
        BigDecimal capacityHours = features.decimal("capacity_hours");
        BigDecimal effortHours = features.decimal("effort_minutes")
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        double elapsed = features.number("elapsed_fraction");

        BigDecimal projectedHours;
        double marginRate;

        if (midSprint && elapsed > 0.1 && effortHours.compareTo(BigDecimal.ZERO) > 0) {
            projectedHours = effortHours.divide(BigDecimal.valueOf(elapsed), 2, RoundingMode.HALF_UP);
            marginRate = 0.10 + (1 - elapsed) * 0.15;
            drivers.add(driver("effort_minutes", "Effort logged so far",
                    projectedHours.multiply(rate),
                    "%s hours burned in the first %.0f%% of the sprint"
                            .formatted(effortHours.toPlainString(), elapsed * 100)));
        } else {
            projectedHours = capacityHours.compareTo(BigDecimal.ZERO) > 0
                    ? capacityHours
                    : features.decimal("team_size", BigDecimal.valueOf(5))
                    .multiply(features.decimal("duration_days", BigDecimal.TEN))
                    .multiply(BigDecimal.valueOf(6));   // six productive hours a day
            marginRate = 0.28;
            drivers.add(driver("capacity_hours", "Planned capacity", projectedHours.multiply(rate),
                    "%s planned hours at %s an hour"
                            .formatted(projectedHours.toPlainString(), rate.toPlainString())));
        }

        BigDecimal overtimeHours = features.decimal("overtime_hours");
        BigDecimal overtimeCost = BigDecimal.ZERO;
        if (overtimeHours.compareTo(BigDecimal.ZERO) > 0) {
            // Overtime is priced at time-and-a-half; the premium is the part that
            // surprises people, so it is called out as its own driver.
            overtimeCost = overtimeHours.multiply(rate).multiply(BigDecimal.valueOf(0.5));
            drivers.add(driver("overtime_hours", "Overtime premium", overtimeCost,
                    "%s overtime hours at 1.5×".formatted(overtimeHours.toPlainString())));
        }

        double volatility = features.number("scope_volatility");
        BigDecimal churnCost = BigDecimal.ZERO;
        if (volatility > 0.05) {
            churnCost = projectedHours.multiply(rate)
                    .multiply(BigDecimal.valueOf(Math.min(volatility * 0.5, 0.2)));
            drivers.add(driver("scope_volatility", "Scope churn rework", churnCost,
                    "%.0f%% scope churn carries rework".formatted(volatility * 100)));
            marginRate += 0.05;
        }

        BigDecimal forecast = projectedHours.multiply(rate).add(overtimeCost).add(churnCost)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = forecast.multiply(BigDecimal.valueOf(marginRate)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal confidence = BigDecimal.valueOf(1 - marginRate).setScale(3, RoundingMode.HALF_UP);

        BigDecimal budget = features.decimal("budget_amount");
        BigDecimal variancePct = budget.compareTo(BigDecimal.ZERO) > 0
                ? forecast.subtract(budget).multiply(BigDecimal.valueOf(100))
                .divide(budget, 2, RoundingMode.HALF_UP)
                : null;

        drivers.sort((left, right) -> right.contribution().abs().compareTo(left.contribution().abs()));
        String explanation = variancePct != null
                ? "Forecast %s ± %s, %s%% against a budget of %s.".formatted(forecast.toPlainString(),
                margin.toPlainString(), variancePct.toPlainString(), budget.toPlainString())
                : "Forecast %s ± %s. Set a sprint budget to see variance."
                .formatted(forecast.toPlainString(), margin.toPlainString());

        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("heuristic", midSprint ? "burn-extrapolation" : "capacity-pricing");
        raw.put("projected_hours", projectedHours);
        raw.put("blended_rate", rate);
        if (variancePct != null) raw.put("variance_vs_budget_pct", variancePct);

        return new InferenceResult(forecast, null, confidence,
                forecast.subtract(margin).max(BigDecimal.ZERO), forecast.add(margin),
                List.copyOf(drivers), explanation, raw, PredictionStatus.SUCCESS, null);
    }

    // ----------------------------------------------------------------- primitives

    private PredictionDriver driver(String feature, String label, BigDecimal contribution, String detail) {
        BigDecimal rounded = contribution.setScale(4, RoundingMode.HALF_UP);
        return rounded.signum() >= 0
                ? PredictionDriver.up(feature, label, rounded, detail)
                : PredictionDriver.down(feature, label, rounded, detail);
    }

    /** Nearest card on the deck, because that is the only scale a team can act on. */
    private BigDecimal snapToScale(BigDecimal raw) {
        BigDecimal best = SCALE[0];
        BigDecimal bestDistance = raw.subtract(best).abs();
        for (BigDecimal candidate : SCALE) {
            BigDecimal distance = raw.subtract(candidate).abs();
            if (distance.compareTo(bestDistance) < 0) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Coefficient of variation, clamped — how erratic a velocity history is. */
    private double variability(List<Object> history) {
        if (history.size() < 2) return 0.4;
        double mean = history.stream().mapToDouble(this::toDouble).average().orElse(0);
        if (mean <= 0) return 0.5;
        double variance = history.stream()
                .mapToDouble(value -> Math.pow(toDouble(value) - mean, 2))
                .average().orElse(0);
        return Math.min(Math.sqrt(variance) / mean, 0.55);
    }

    private double ratio(double numerator, double denominator) {
        return denominator <= 0 ? 0 : numerator / denominator;
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
