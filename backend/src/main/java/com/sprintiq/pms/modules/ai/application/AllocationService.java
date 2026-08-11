package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.modules.ai.application.port.DeveloperFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.EffortFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.ai.application.port.WorkItemFeatureSource;
import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Model 3 — ranks developers for a task or story.
 *
 * <p>Ranking is done per candidate and assembled here rather than asking the model for
 * an ordered list. Scoring each person independently means the explanation attached to
 * a candidate is about that candidate — "80% skill match, but already at 130% load" —
 * rather than a statement about their position relative to people they have never met.
 * It also means the ranking degrades gracefully: if one candidate's features cannot be
 * assembled, the others still rank.</p>
 *
 * <p>Suggestions only. Nothing here assigns anything; assignment stays an explicit human
 * action through the tasks module. A tool that hands out work automatically is one that
 * gets switched off after the first time it hands the wrong work to the wrong person.</p>
 */
@Service
@RequiredArgsConstructor
public class AllocationService {

    /** Above this share of a sprint's capacity, a candidate is flagged rather than hidden. */
    private static final BigDecimal OVERLOAD_THRESHOLD = BigDecimal.valueOf(100);

    private static final int DEFAULT_LIMIT = 5;

    private final AiInferenceService inference;
    private final List<WorkItemFeatureSource> workItemSources;
    private final DeveloperFeatureSource developers;
    private final ProjectContextSource projects;
    private final EffortFeatureSource effort;
    private final AllocationSuggestionRepository suggestions;

    @Transactional
    public AllocationRankingResponse rank(EntityType entityType, UUID entityId,
                                          List<UUID> candidateIds, Integer limit) {
        WorkItemFeatureSource source = sourceFor(entityType);
        WorkItemFeatureSource.WorkItemFeatures item = source.workItemFor(entityId);
        ProjectContextSource.ProjectContext project = projects.contextFor(item.projectId());

        inference.requireCapability(AiModelCode.M3_ALLOCATION, project.workspaceId());

        List<DeveloperFeatureSource.DeveloperFeatures> pool =
                candidateIds == null || candidateIds.isEmpty()
                        ? developers.candidatesFor(item.projectId())
                        : developers.candidatesByIds(item.projectId(), candidateIds);

        if (pool.isEmpty()) {
            throw new BusinessRuleException(
                    "No project members to rank. Add people to the project first.");
        }

        Map<UUID, BigDecimal> loads = effort.loadByUser(item.projectId());
        List<String> requiredSkills = item.skillTags();

        // One prediction row per ranking, shared by every candidate: the request is
        // "who should do this", asked once. Scoring calls hang off it as suggestions.
        AiPrediction rankingPrediction = null;
        List<Scored> scored = new ArrayList<>(pool.size());

        for (DeveloperFeatureSource.DeveloperFeatures candidate : pool) {
            BigDecimal load = loads.getOrDefault(candidate.userId(),
                    candidate.currentLoadPct() == null ? BigDecimal.ZERO : candidate.currentLoadPct());
            BigDecimal skillMatch = skillMatch(requiredSkills, candidate.skills());

            FeatureVector vector = candidateVector(item, candidate, load, skillMatch, requiredSkills);
            AiPrediction prediction = inference.infer(new AiInferenceService.InferenceCommand(
                    AiModelCode.M3_ALLOCATION, entityType, entityId,
                    item.projectId(), project.workspaceId(), vector));

            if (!prediction.isUsable() || prediction.getPredictedValue() == null) {
                continue;
            }
            if (rankingPrediction == null) {
                rankingPrediction = prediction;
            }
            scored.add(new Scored(candidate, prediction, load, skillMatch));
        }

        if (scored.isEmpty()) {
            throw new BusinessRuleException(
                    "The allocation model could not score any candidate right now. Assign manually.");
        }

        scored.sort(Comparator.comparing((Scored entry) -> entry.prediction().getPredictedValue()).reversed());
        int cap = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, scored.size());

        List<AllocationCandidateResponse> ranked = new ArrayList<>(cap);
        List<AllocationSuggestion> rows = new ArrayList<>(cap);

        for (int index = 0; index < Math.min(cap, scored.size()); index++) {
            Scored entry = scored.get(index);
            short position = (short) (index + 1);
            boolean overloaded = entry.load().compareTo(OVERLOAD_THRESHOLD) >= 0;

            BigDecimal predictedPerformance = predictedPerformance(entry);

            AllocationSuggestion row = AllocationSuggestion.builder()
                    .projectId(item.projectId())
                    .taskId(entityType == EntityType.TASK ? entityId : null)
                    .storyId(entityType == EntityType.STORY ? entityId : null)
                    .candidateUserId(entry.candidate().userId())
                    .rankPosition(position)
                    .fitScore(entry.prediction().getPredictedValue())
                    .predictedPerformance(predictedPerformance)
                    .consistencyScore(entry.candidate().consistencyScore())
                    .learningRate(entry.candidate().learningRate())
                    .skillMatchRatio(entry.skillMatch())
                    .currentLoadPct(entry.load())
                    .overloadRisk(overloaded)
                    .reasoning(entry.prediction().getDrivers())
                    .explanation(entry.prediction().getExplanation())
                    .predictionId(entry.prediction().getId())
                    .build();
            row.setOrganizationId(TenantContext.requireOrganizationId());
            rows.add(row);

            ranked.add(new AllocationCandidateResponse(
                    entry.candidate().userId(), entry.candidate().fullName(), entry.candidate().avatarUrl(),
                    position, entry.prediction().getPredictedValue(), predictedPerformance,
                    entry.candidate().consistencyScore(), entry.candidate().learningRate(),
                    entry.skillMatch(), entry.load(), overloaded,
                    entry.prediction().getDrivers().stream()
                            .map(PredictionDriverResponse::from).toList(),
                    entry.prediction().getExplanation()));
        }

        suggestions.saveAll(rows);

        return new AllocationRankingResponse(entityType, entityId, item.itemKey(),
                item.currentAssigneeId(), requiredSkills, ranked,
                rankingPrediction == null ? null : rankingPrediction.getId(),
                java.time.Instant.now());
    }

    /**
     * Marks the candidate the team actually picked.
     *
     * <p>Called after an assignment lands. Without it the suggestion table records only
     * what was proposed, and the single most valuable question about this model — how
     * often does the team pick the person it recommended — has no answer.</p>
     */
    @Transactional
    public void recordChoice(EntityType entityType, UUID entityId, UUID chosenUserId) {
        List<AllocationSuggestion> rows = entityType == EntityType.TASK
                ? suggestions.findByTaskIdOrderByCreatedAtDescRankPositionAsc(entityId)
                : suggestions.findByStoryIdOrderByCreatedAtDescRankPositionAsc(entityId);
        if (rows.isEmpty()) {
            return;
        }
        // Only the most recent ranking is relevant; older ones described a different
        // version of the item and a different team.
        UUID latestPrediction = rows.get(0).getPredictionId();
        List<AllocationSuggestion> updated = new ArrayList<>();
        for (AllocationSuggestion row : rows) {
            if (!Objects.equals(row.getPredictionId(), latestPrediction)) break;
            if (row.getCandidateUserId().equals(chosenUserId) && !row.isChosen()) {
                row.setChosen(true);
                updated.add(row);
            }
        }
        if (!updated.isEmpty()) {
            suggestions.saveAll(updated);
        }
    }

    // ------------------------------------------------------------------ internals

    private record Scored(DeveloperFeatureSource.DeveloperFeatures candidate,
                          AiPrediction prediction,
                          BigDecimal load,
                          BigDecimal skillMatch) {}

    private WorkItemFeatureSource sourceFor(EntityType entityType) {
        return workItemSources.stream()
                .filter(source -> source.supports(entityType))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException(
                        "Allocation is only available for tasks and stories"));
    }

    private FeatureVector candidateVector(WorkItemFeatureSource.WorkItemFeatures item,
                                          DeveloperFeatureSource.DeveloperFeatures candidate,
                                          BigDecimal load, BigDecimal skillMatch,
                                          List<String> requiredSkills) {
        return FeatureVector.create()
                .put("item_type", item.type())
                .put("item_priority", item.priority().name())
                .putNumber("item_points", item.storyPoints())
                .putNumber("item_estimated_minutes", item.estimatedMinutes())
                .putNumber("required_skill_count", requiredSkills.size())
                .put("candidate_id", candidate.userId().toString())
                .put("seniority", candidate.seniority())
                .putNumber("years_experience", candidate.yearsExperience())
                .putNumber("avg_velocity", candidate.avgVelocity())
                .putNumber("consistency_score", candidate.consistencyScore())
                .putNumber("learning_rate", candidate.learningRate())
                .putNumber("defect_rate", candidate.defectRate())
                .putNumber("reopen_rate", candidate.reopenRate())
                .putNumber("blocked_frequency", candidate.blockedFrequency())
                .putNumber("completion_variance", candidate.completionVariance())
                .putNumber("tasks_completed", candidate.tasksCompleted())
                .putNumber("availability_rate", candidate.availabilityRate())
                .putNumber("weekly_capacity_hours", candidate.weeklyCapacityHours())
                .putNumber("current_load_pct", load)
                .putNumber("skill_match_ratio", skillMatch);
    }

    /**
     * Share of the item's skill tags the candidate holds at working proficiency (3+).
     *
     * <p>An item with no tags scores everyone at 1.0 rather than 0.0. Zero would mean
     * "nobody is qualified", which is the opposite of what an untagged task implies, and
     * it would flatten the ranking to nothing but workload.</p>
     */
    private BigDecimal skillMatch(List<String> required, Map<String, Short> held) {
        if (required == null || required.isEmpty()) {
            return BigDecimal.ONE;
        }
        long matched = required.stream()
                .map(skill -> held.get(skill.toLowerCase(Locale.ROOT)))
                .filter(proficiency -> proficiency != null && proficiency >= 3)
                .count();
        return BigDecimal.valueOf(matched)
                .divide(BigDecimal.valueOf(required.size()), 3, RoundingMode.HALF_UP);
    }

    private BigDecimal predictedPerformance(Scored entry) {
        Object raw = entry.prediction().getRawResponse() == null
                ? null
                : entry.prediction().getRawResponse().get("predicted_performance");
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        if (raw != null) {
            try {
                return new BigDecimal(raw.toString()).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException ignored) {
                // fall through to the derived value below
            }
        }
        BigDecimal velocity = entry.candidate().avgVelocity();
        return velocity == null ? null : velocity.multiply(entry.prediction().getPredictedValue())
                .setScale(2, RoundingMode.HALF_UP);
    }
}
