package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.application.port.CollaborationFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.EffortFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Model 2 — over-budget, requirement-change and collaboration risk for a sprint.
 *
 * <p>All three classifiers read one shared feature vector. They are asking different
 * questions of the same sprint, and assembling the features three times would let the
 * widgets on one dashboard disagree about how much scope had changed.</p>
 *
 * <p>The collaboration classifier is the odd one out in two ways, both deliberate. Its
 * inputs come from comment threads rather than from the sprint row, so they arrive
 * through their own port; and it is the one model here that declines to answer rather
 * than scoring an empty sprint, because three of its five readings are healthiest at
 * zero and an untouched sprint would otherwise be handed a clean bill it has not
 * earned.</p>
 */
@Service
@RequiredArgsConstructor
public class RiskService {

    private final AiInferenceService inference;
    private final SprintFeatureSource sprints;
    private final ProjectContextSource projects;
    private final EffortFeatureSource effort;
    /** For the story-text readings the requirement-change model shares with M1. */
    private final StoryFeatureSource stories;
    /** Comment threads and board movement, for the collaboration model. */
    private final CollaborationFeatureSource collaboration;
    private final RiskAssessmentRepository assessments;
    private final SprintRiskAdjustmentService adjustmentService;

    @Transactional
    public SprintRiskResponse assess(UUID sprintId) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        ProjectContextSource.ProjectContext project = projects.contextFor(sprint.projectId());
        FeatureVector shared = sharedFeatures(sprint, project);

        RiskAssessmentResponse overBudget = assessOne(RiskType.OVER_BUDGET, sprint, project, shared);
        RiskAssessmentResponse requirementChange =
                assessOne(RiskType.REQUIREMENT_CHANGE, sprint, project, shared);
        RiskAssessmentResponse communicationCollaboration =
                assessOne(RiskType.COMMUNICATION_COLLABORATION, sprint, project, shared);

        RiskSeverity highest = highestOf(overBudget, requirementChange, communicationCollaboration);
        return new SprintRiskResponse(sprintId, overBudget, requirementChange,
                communicationCollaboration, highest);
    }

    @Transactional
    public RiskAssessmentResponse assess(UUID sprintId, RiskType riskType) {
        SprintFeatureSource.SprintFeatures sprint = sprints.featuresFor(sprintId);
        ProjectContextSource.ProjectContext project = projects.contextFor(sprint.projectId());
        return assessOne(riskType, sprint, project, sharedFeatures(sprint, project));
    }

    /**
     * The stored scorings, with the board activity since each one folded in.
     *
     * <p>The overlay is what keeps this figure from going stale between assessments. A
     * risk number that only moves when somebody presses <em>Assess</em> teaches a team
     * that it is decorative, and the events that matter most — a task blocked, work
     * reopened, scope admitted — all arrive between scorings.</p>
     */
    @Transactional(readOnly = true)
    public SprintRiskResponse latest(UUID sprintId) {
        SprintRiskAdjustmentService.Ledger ledger = adjustmentService.ledgerFor(sprintId);
        RiskAssessmentResponse overBudget = adjusted(sprintId, RiskType.OVER_BUDGET, ledger);
        RiskAssessmentResponse requirementChange =
                adjusted(sprintId, RiskType.REQUIREMENT_CHANGE, ledger);
        RiskAssessmentResponse collaboration =
                adjusted(sprintId, RiskType.COMMUNICATION_COLLABORATION, ledger);
        return new SprintRiskResponse(sprintId, overBudget, requirementChange, collaboration,
                highestOf(overBudget, requirementChange, collaboration));
    }

    /** The ledger behind an adjusted score — what the board has recorded since each scoring. */
    @Transactional(readOnly = true)
    public List<RiskAdjustmentResponse> adjustments(UUID sprintId) {
        return adjustmentService.ledgerFor(sprintId).entries().stream()
                .map(RiskAdjustmentResponse::from).toList();
    }

    private RiskAssessmentResponse adjusted(UUID sprintId, RiskType riskType,
                                            SprintRiskAdjustmentService.Ledger ledger) {
        return assessments.findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(sprintId, riskType)
                .map(assessment -> RiskAssessmentResponse.from(
                        assessment, ledger.netSince(riskType, assessment.getCreatedAt())))
                .orElse(null);
    }

    /** The risk score's history for one sprint — the trend a retrospective reads. */
    @Transactional(readOnly = true)
    public List<RiskAssessmentResponse> history(UUID sprintId, RiskType riskType) {
        return assessments.findBySprintIdAndRiskTypeOrderByCreatedAtAsc(sprintId, riskType).stream()
                .map(RiskAssessmentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RiskAssessmentResponse> forProject(UUID projectId, int limit) {
        return assessments.findLatestForProject(projectId, PageRequest.of(0, limit)).stream()
                .map(RiskAssessmentResponse::from).toList();
    }

    @Transactional
    public RiskAssessmentResponse acknowledge(UUID assessmentId) {
        RiskAssessment assessment = assessments.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("RiskAssessment", assessmentId));
        assessment.acknowledge(TenantContext.requireUserId());
        return RiskAssessmentResponse.from(assessments.save(assessment));
    }

    // ------------------------------------------------------------------ internals

    private FeatureVector sharedFeatures(SprintFeatureSource.SprintFeatures sprint,
                                         ProjectContextSource.ProjectContext project) {
        long effortMinutes = effort.sprintEffortMinutes(sprint.sprintId());
        long overtimeMinutes = effort.sprintOvertimeMinutes(sprint.sprintId());

        // Budgeted minutes come from planned capacity hours; without them the burn ratio
        // is meaningless, so it is left at zero rather than divided by a guess.
        BigDecimal capacityMinutes = sprint.capacityHours() == null
                ? BigDecimal.ZERO
                : sprint.capacityHours().multiply(BigDecimal.valueOf(60));
        BigDecimal burnRatio = capacityMinutes.compareTo(BigDecimal.ZERO) > 0
                ? BigDecimal.valueOf(effortMinutes).divide(capacityMinutes, 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return FeatureVector.create()
                .putNumber("committed_points", sprint.committedPoints())
                .putNumber("completed_points", sprint.completedPoints())
                .putNumber("capacity_points", sprint.capacityPoints())
                .putNumber("capacity_hours", sprint.capacityHours())
                .putNumber("commitment_ratio", sprint.commitmentRatio())
                .putNumber("scope_volatility", sprint.scopeVolatility())
                .putNumber("elapsed_fraction", sprint.elapsedFraction())
                .putNumber("effort_minutes", effortMinutes)
                .putNumber("overtime_minutes", overtimeMinutes)
                .putNumber("effort_burn_ratio", burnRatio)
                .putNumber("story_count", sprint.storyCount())
                .putNumber("completed_story_count", sprint.completedStoryCount())
                .putNumber("blocked_count", sprint.blockedCount())
                .putNumber("defect_count", sprint.defectCount())
                .putNumber("reopened_count", sprint.reopenedCount())
                .putNumber("unestimated_count", sprint.unestimatedCount())
                .putNumber("contributor_count", sprint.contributorCount())
                .putNumber("team_size", sprint.teamSize())
                .putNumber("absence_days", sprint.absenceDays())
                .putNumber("history_depth", sprint.historicalVelocities().size())
                .putNumber("requirement_count", project.requirementCount())
                .putNumber("post_approval_changes", project.postApprovalChanges())
                .putNumber("requirement_churn_rate", project.churnRate())
                .put("has_sprint_goal", sprint.goal() != null && !sprint.goal().isBlank());
    }

    /**
     * The over-budget model's own columns.
     *
     * <p>Everything here is knowable at planning or mid-sprint. The model was trained
     * with the post-hoc columns — actual hours, effort deviation, the final tallies —
     * deliberately excluded, because they are what the label is computed from. Sending
     * an approximation of one would produce a model that scores perfectly and forecasts
     * nothing, so the fields simply are not here.</p>
     *
     * <p>{@code base_velocity} and {@code velocity_rolling_prev} are the same rolling
     * figure the velocity model reads. That is deliberate: two models disagreeing
     * because one of them was shown a different history is a bug that never surfaces,
     * since both answers look reasonable on their own.</p>
     */
    private FeatureVector overBudgetFeatures(SprintFeatureSource.SprintFeatures sprint,
                                             FeatureVector shared) {
        EffortFeatureSource.SprintSlippage slippage = effort.sprintSlippage(sprint.sprintId());
        BigDecimal capacityHours = sprint.capacityHours();
        BigDecimal capacityPoints = sprint.capacityPoints();
        BigDecimal hoursPerPoint = positive(capacityHours) && positive(capacityPoints)
                ? capacityHours.divide(capacityPoints, 3, RoundingMode.HALF_UP)
                : null;

        BigDecimal committed = sprint.committedPoints();
        BigDecimal plannedHours = hoursPerPoint != null && committed != null
                ? committed.multiply(hoursPerPoint)
                : null;

        // Average points per story: the closest thing the app records to the
        // "complexity estimated at planning" the model was trained on.
        BigDecimal complexity = sprint.storyCount() > 0 && committed != null
                ? committed.divide(BigDecimal.valueOf(sprint.storyCount()), 3, RoundingMode.HALF_UP)
                : null;

        long overtimeMinutes = effort.sprintOvertimeMinutes(sprint.sprintId());
        BigDecimal fatigue = positive(capacityHours)
                ? BigDecimal.valueOf(overtimeMinutes)
                        .divide(capacityHours.multiply(BigDecimal.valueOf(60)), 3, RoundingMode.HALF_UP)
                        .min(BigDecimal.ONE)
                : null;

        return shared
                .put("team_size", teamSizeFor(sprint))
                .put("base_velocity", sprint.rollingVelocity())
                .put("velocity_rolling_prev", sprint.rollingVelocity())
                .put("velocity_trend", velocityTrend(sprint))
                .put("complexity", complexity)
                .put("hours_per_point", hoursPerPoint)
                .put("sprint_capacity_hours", capacityHours)
                .put("planned_hours", plannedHours)
                .put("committed_story_points", committed)
                // The app records these for the sprint in flight; the model wants the
                // previous sprint's. Named for what they are so the mapping is not
                // mistaken for history the app does not keep.
                .putNumber("prev_blocked_tasks", sprint.blockedCount())
                .putNumber("prev_reopened_tasks", sprint.reopenedCount())
                .putNumber("prev_rework_score", sprint.reopenedCount() + sprint.defectCount())
                .put("fatigue", fatigue)
                .put("scope_added", sprint.addedPoints())
                // Mid-sprint slippage. Everything above this line is knowable at
                // planning and does not move, so without these the sprint scores the
                // same on day nine as it did on day one however badly it is going.
                // "Behind" is measured against each task's own estimate rather than
                // against the sprint's end date, so it says something before the last
                // day — see EffortFeatureSource.SprintSlippage.
                //
                // The deployed CatBoost bundle does not have columns for these; its
                // feature list is fixed at training time and the service drops what it
                // does not recognise. They are sent anyway, and that is deliberate:
                // they drive the heuristic client, they are on the prediction's stored
                // payload so a retrain has the history to fit against, and a feature
                // the backend computes but never sends is one nobody discovers is
                // missing. The columns join the model at its next training run.
                .putNumber("tasks_not_started_past_half", slippage.tasksNotStartedPastHalf())
                .putNumber("tasks_over_estimate", slippage.tasksOverEstimate())
                .putNumber("minutes_over_estimate", slippage.minutesOverEstimate())
                .putNumber("task_slippage_rate", slippage.slippageRate());
    }

    /**
     * The requirement-change model's own columns.
     *
     * <p>Four of them — average story length, criteria per story, stories with none, and
     * the count of hedging words — come from {@link StoryFeatureSource#storyMetricsForSprint},
     * the same reading of the same story text the story-point model estimates from.
     * Measuring it once and feeding both is the point.</p>
     */
    private FeatureVector requirementChangeFeatures(SprintFeatureSource.SprintFeatures sprint,
                                                    ProjectContextSource.ProjectContext project,
                                                    FeatureVector shared) {
        StoryFeatureSource.SprintStoryMetrics metrics = stories.storyMetricsForSprint(sprint.sprintId());

        // How well specified the sprint is, as the fraction of stories that have any
        // acceptance criteria at all. Null when there are no stories, because zero
        // would read as "documented terribly" rather than "nothing to document".
        BigDecimal docQuality = metrics.storyCount() > 0
                ? BigDecimal.ONE.subtract(BigDecimal.valueOf(metrics.storiesWithoutCriteria())
                        .divide(BigDecimal.valueOf(metrics.storyCount()), 3, RoundingMode.HALF_UP))
                : null;

        // Whether this sprint's predecessor was already flagged High. The model was
        // trained with that flag, and the only honest source for it is this model's
        // own recorded history.
        boolean previouslyHigh = assessments
                .findFirstBySprintIdAndRiskTypeOrderByCreatedAtDesc(
                        sprint.sprintId(), RiskType.REQUIREMENT_CHANGE)
                .map(assessment -> assessment.getSeverity() == RiskSeverity.HIGH)
                .orElse(false);

        return shared
                .put("project_domain", project.domain())
                .put("project_status", project.status())
                .put("dev_experience_level", project.devExperienceLevel())
                .putNumber("baseline_story_count", metrics.storyCount())
                .put("avg_story_length_words", metrics.averageWords())
                .put("avg_acceptance_criteria_per_story", metrics.averageCriteria())
                .putNumber("stories_without_AC", metrics.storiesWithoutCriteria())
                .putNumber("ambiguity_marker_count", metrics.ambiguityMarkers())
                .put("requirements_doc_quality", docQuality)
                .put("prev_change_requests_count", project.postApprovalChanges())
                .put("prev_requirement_volatility_score", project.churnRate())
                .put("prev_story_change_ratio", sprint.scopeVolatility())
                .putNumber("prev_high_risk_flag", previouslyHigh ? 1 : 0);
    }

    /**
     * The collaboration model's own columns.
     *
     * <p>Five readings, named for the corpus the classifier was fine-tuned on rather
     * than for anything in this codebase: {@code avg_response_time},
     * {@code comments_per_task}, {@code inactive_days}, {@code blockers} and
     * {@code reopened_tasks}. The service turns them into one sentence and reads that,
     * so a renamed key here is not a mapping error the model rejects — it is a zero it
     * accepts, and the score comes back looking fine.</p>
     *
     * <p>The blocked and reopened counts come from the collaboration port rather than
     * from {@code shared}, and the difference is not redundancy. The shared vector's
     * figures are story-level, off the backlog snapshot; these are task-level, and the
     * conversation that failed happened on a task. A story is only blocked once its
     * tasks are, so the story figure lags the signal this model exists to catch.</p>
     */
    private FeatureVector collaborationFeatures(
            CollaborationFeatureSource.SprintCollaboration signals, FeatureVector shared) {
        return shared
                // Null means no thread had two participants — see the port. Left absent
                // rather than sent as zero, which on this scale is the best possible
                // reading and would turn silence into an instant reply.
                .put("avg_response_time", signals.avgResponseTimeHours())
                .putNumber("comments_per_task", signals.commentsPerTask())
                .putNumber("inactive_days", signals.inactiveDays())
                .putNumber("blockers", signals.blockedTasks())
                .putNumber("reopened_tasks", signals.reopenedTasks())
                // Context for the audit trail and the panel, not for the model: the
                // service builds its sentence from the five above and ignores the rest.
                .putNumber("collaboration_task_count", signals.taskCount())
                .putNumber("collaboration_comment_count", signals.commentCount())
                .putNumber("longest_silence_days", signals.longestSilenceDays())
                .putNumber("unanswered_threads", signals.unansweredThreads())
                .putNumber("collaboration_contributor_count", signals.contributorCount());
    }

    /** The stretch of time the collaboration readings describe. */
    private record Window(Instant start, Instant end) {}

    /**
     * The sprint's window for the collaboration readings.
     *
     * <p>Ends now, or at the sprint's end date once that has passed — never in the
     * future. A window running to a date the sprint has not reached would count every
     * day still to come as a day on which nobody spoke, so a sprint planned for next
     * month would open at maximum risk.</p>
     *
     * <p>A sprint with no start date falls back to the last fortnight: the readings are
     * rates and counts, and over an unbounded window every one of them grows without
     * saying anything.</p>
     */
    private Window collaborationWindow(SprintFeatureSource.SprintFeatures sprint) {
        Instant now = Instant.now();
        Instant start = sprint.startDate() == null
                ? now.minus(Duration.ofDays(14))
                : sprint.startDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = sprint.endDate() == null
                ? now
                : sprint.endDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return new Window(start, end.isBefore(now) ? end : now);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal teamSizeFor(SprintFeatureSource.SprintFeatures sprint) {
        if (sprint.teamSize() != null && sprint.teamSize() > 0) {
            return BigDecimal.valueOf(sprint.teamSize());
        }
        return sprint.contributorCount() > 0 ? BigDecimal.valueOf(sprint.contributorCount()) : null;
    }

    /** Last closed sprint's velocity against the one before it. Null with too little history. */
    private BigDecimal velocityTrend(SprintFeatureSource.SprintFeatures sprint) {
        List<BigDecimal> history = sprint.historicalVelocities();
        if (history == null || history.size() < 2) {
            return null;
        }
        return history.get(history.size() - 1).subtract(history.get(history.size() - 2));
    }

    private RiskAssessmentResponse assessOne(RiskType riskType,
                                             SprintFeatureSource.SprintFeatures sprint,
                                             ProjectContextSource.ProjectContext project,
                                             FeatureVector features) {
        // A fresh copy per model. Both risk types are assessed from the same shared
        // vector in one call, so mutating it in place would hand the second model the
        // first one's columns — and since the feature hash is the cache key, the
        // contamination would persist for six hours without anything looking wrong.
        FeatureVector base = FeatureVector.create().putAll(features.asMap());

        // Collaboration is the one model that can decline before it is called. Three of
        // its five readings are healthiest at zero, so an untouched sprint assembles a
        // perfectly well-formed vector that the model reads as a well-run team — a
        // confident all-clear for a sprint nobody has started. Returning null instead
        // puts the panel in its "nothing to read yet" state, which is the true
        // statement, and costs nothing rather than billing for the inference.
        CollaborationFeatureSource.SprintCollaboration signals = null;
        if (riskType == RiskType.COMMUNICATION_COLLABORATION) {
            Window window = collaborationWindow(sprint);
            signals = collaboration.collaborationFor(sprint.sprintId(), window.start(), window.end());
            if (!signals.hasSignal()) {
                return null;
            }
        }

        // A switch expression rather than a statement, so a fourth risk type is a
        // compile error here instead of a model that silently scores on the wrong
        // columns.
        FeatureVector vector = switch (riskType) {
            case OVER_BUDGET -> overBudgetFeatures(sprint, base);
            case REQUIREMENT_CHANGE -> requirementChangeFeatures(sprint, project, base);
            case COMMUNICATION_COLLABORATION -> collaborationFeatures(signals, base);
        };

        AiPrediction prediction = inference.infer(new AiInferenceService.InferenceCommand(
                riskType.getModelCode(), EntityType.SPRINT, sprint.sprintId(),
                sprint.projectId(), project.workspaceId(), vector));

        if (!prediction.isUsable() || prediction.getPredictedValue() == null) {
            return null;
        }

        BigDecimal score = prediction.getPredictedValue();
        List<String> mitigations = new ArrayList<>();
        Object raw = prediction.getRawResponse() == null ? null : prediction.getRawResponse().get("mitigations");
        if (raw instanceof List<?> list) {
            list.forEach(item -> mitigations.add(String.valueOf(item)));
        }

        RiskAssessment assessment = RiskAssessment.builder()
                .projectId(sprint.projectId())
                .sprintId(sprint.sprintId())
                .riskType(riskType)
                .riskScore(score)
                .riskLabel(atRisk(prediction, score))
                .severity(severityOf(score))
                .confidence(prediction.getConfidence())
                .drivers(prediction.getDrivers())
                .mitigations(mitigations)
                .explanation(prediction.getExplanation())
                .predictionId(prediction.getId())
                .build();
        assessment.setOrganizationId(TenantContext.requireOrganizationId());

        return RiskAssessmentResponse.from(assessments.save(assessment));
    }

    /**
     * Score to severity. The bands are wide on purpose: a classifier that has not been
     * calibrated against outcomes cannot justify finer distinctions, and four labels are
     * as much as a dashboard can usefully colour anyway.
     */
    /**
     * Whether the model is calling this sprint at risk.
     *
     * <p>The mock says so in words. The CatBoost models answer with a probability from
     * their binary head, and 0.5 is the threshold that head's own {@code predict} uses,
     * so reading the number is the same question asked numerically rather than a new
     * threshold invented here.</p>
     *
     * <p>This is deliberately a different question from {@link #severityOf}: severity is
     * a band for colouring a dashboard, and "at risk" is the model's own binary call.
     * A 0.48 sprint shows as MEDIUM without being flagged, which is the honest reading
     * of a model that is not sure.</p>
     */
    // Package-private rather than private so the vocabulary mismatch this exists to
    // fix can be pinned by a test without standing up the whole service.
    boolean atRisk(AiPrediction prediction, BigDecimal score) {
        if ("AT_RISK".equals(prediction.getPredictedLabel())) {
            return true;
        }
        return score != null && score.compareTo(BigDecimal.valueOf(0.5)) >= 0;
    }

    RiskSeverity severityOf(BigDecimal score) {
        return RiskSeverity.forScore(score);
    }

    /**
     * The loudest of the three, read from the adjusted score rather than the stored one.
     *
     * <p>The banner is the thing people act on, so it has to agree with the panels
     * underneath it. A sprint whose over-budget number has been pushed into the red by
     * this morning's blockers, still headed "Medium risk" because that is what the model
     * said on Monday, is the exact failure the overlay exists to prevent.</p>
     */
    private RiskSeverity highestOf(RiskAssessmentResponse... responses) {
        RiskSeverity highest = RiskSeverity.LOW;
        for (RiskAssessmentResponse response : responses) {
            if (response == null) {
                continue;
            }
            RiskSeverity severity = response.adjustedSeverity() == null
                    ? response.severity() : response.adjustedSeverity();
            if (severity != null && severity.ordinal() > highest.ordinal()) {
                highest = severity;
            }
        }
        return highest;
    }
}
