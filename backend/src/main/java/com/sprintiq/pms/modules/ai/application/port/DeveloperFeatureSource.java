package com.sprintiq.pms.modules.ai.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The candidate pool for Model 3, with the behavioural metrics the ranking is built on.
 *
 * <p>These are the fields on {@code developer_profiles} that a nightly job derives from
 * delivery history — average velocity, consistency, learning rate, defect and reopen
 * rates. Deriving them on the fly inside the AI module would mean the ranking depends on
 * whichever stories happened to load, so a candidate's score would change between two
 * clicks. They are computed once, stored, and read here.</p>
 */
public interface DeveloperFeatureSource {

    /**
     * @param consistencyScore 0..1; how tightly this person's delivery clusters around
     *                         their own average. A steady 5-points-a-sprint developer is
     *                         easier to plan around than one alternating 2 and 9.
     * @param learningRate     improvement per sprint in points, positive or negative
     * @param currentLoadPct   assigned open work as a percentage of a sprint's capacity
     * @param skills           skill name (lower-cased) to proficiency 1..5
     */
    record DeveloperFeatures(UUID userId,
                             String fullName,
                             String avatarUrl,
                             String seniority,
                             BigDecimal yearsExperience,
                             BigDecimal hourlyRate,
                             BigDecimal weeklyCapacityHours,
                             BigDecimal availabilityRate,
                             BigDecimal avgVelocity,
                             BigDecimal consistencyScore,
                             BigDecimal learningRate,
                             BigDecimal defectRate,
                             BigDecimal reopenRate,
                             BigDecimal blockedFrequency,
                             BigDecimal completionVariance,
                             int tasksCompleted,
                             BigDecimal currentLoadPct,
                             Map<String, Short> skills) {}

    /** Everyone who could be assigned work on this project, with metrics attached. */
    List<DeveloperFeatures> candidatesFor(UUID projectId);

    /** A specific subset — used when the caller has already narrowed the pool. */
    List<DeveloperFeatures> candidatesByIds(UUID projectId, List<UUID> userIds);

    /** Blended hourly rate across a project's members, for cost forecasting. */
    BigDecimal averageHourlyRate(UUID projectId);
}
