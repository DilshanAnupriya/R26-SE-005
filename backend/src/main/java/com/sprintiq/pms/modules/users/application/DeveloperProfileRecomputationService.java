package com.sprintiq.pms.modules.users.application;

import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.backlog.domain.StoryType;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import com.sprintiq.pms.modules.users.domain.DeveloperProfile;
import com.sprintiq.pms.modules.users.domain.DeveloperProfileRepository;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Derives and updates developer behavioral metrics across sprints.
 *
 * <p>Recomputed automatically on sprint completion and available on-demand.
 * Computes deterministic statistics (velocity, defect rate, reopen rate,
 * blocker frequency, variance, and baseline consistency / learning rate) from
 * historical delivery records in PostgreSQL, and synchronizes with the AI
 * Optimizer tables.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeveloperProfileRecomputationService {

    private final DeveloperProfileRepository profileRepository;
    private final StoryRepository storyRepository;
    private final SprintRepository sprintRepository;
    private final UserRepository userRepository;

    @Autowired(required = false)
    private com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperRepository optimizerDeveloperRepository;

    @Autowired(required = false)
    private com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperProfileRepository optimizerDeveloperProfileRepository;

    /**
     * Recomputes behavioral metrics for a single user across their historical deliveries.
     */
    @Transactional
    public DeveloperProfile recomputeForUser(UUID userId) {
        log.debug("Recomputing behavioral metrics for user {}", userId);

        DeveloperProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    DeveloperProfile created = DeveloperProfile.builder().userId(userId).build();
                    TenantContext.organizationId().ifPresent(created::setOrganizationId);
                    return created;
                });

        List<Story> assignedStories = storyRepository.findByAssigneeIdAndDeletedAtIsNull(userId);
        if (assignedStories.isEmpty()) {
            profile.setTasksCompleted(0);
            profile.setAvgVelocity(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            profile.setDefectRate(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            profile.setReopenRate(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            profile.setBlockedFrequency(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            profile.setCompletionVariance(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
            profile.setConsistencyScore(BigDecimal.valueOf(0.500).setScale(3, RoundingMode.HALF_UP));
            profile.setLearningRate(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            profile.setLastComputedAt(Instant.now());
            DeveloperProfile saved = profileRepository.save(profile);
            syncToOptimizer(userId, saved);
            return saved;
        }

        // 1. Filter completed stories
        List<Story> completedStories = assignedStories.stream()
                .filter(s -> s.getStatus() != null && s.getStatus().isCompleted())
                .toList();

        int tasksCompleted = completedStories.size();
        profile.setTasksCompleted(tasksCompleted);

        // 2. Identify sprints for completed stories
        Set<UUID> sprintIds = completedStories.stream()
                .map(Story::getSprintId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, Sprint> sprintsById = new HashMap<>();
        if (!sprintIds.isEmpty()) {
            sprintRepository.findAllById(sprintIds).forEach(s -> sprintsById.put(s.getId(), s));
        }

        // Group points delivered per sprint (only completed sprints or sprints with completed work)
        Map<UUID, BigDecimal> pointsPerSprint = new LinkedHashMap<>();
        for (Story story : completedStories) {
            UUID spId = story.getSprintId();
            if (spId != null) {
                BigDecimal spPoints = story.velocityPoints();
                pointsPerSprint.merge(spId, spPoints, BigDecimal::add);
            }
        }

        // 3. Average Velocity
        int distinctSprints = pointsPerSprint.size();
        if (distinctSprints > 0) {
            BigDecimal totalDeliveredPoints = pointsPerSprint.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgVelocity = totalDeliveredPoints.divide(
                    BigDecimal.valueOf(distinctSprints), 2, RoundingMode.HALF_UP);
            profile.setAvgVelocity(avgVelocity);
        } else if (tasksCompleted > 0) {
            BigDecimal totalPoints = completedStories.stream()
                    .map(Story::velocityPoints)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            profile.setAvgVelocity(totalPoints.setScale(2, RoundingMode.HALF_UP));
        } else {
            profile.setAvgVelocity(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }

        // 4. Defect Rate (Bugs assigned / total completed stories)
        long defectCount = completedStories.stream()
                .filter(s -> s.getType() == StoryType.BUG)
                .count();
        BigDecimal defectRate = tasksCompleted > 0
                ? BigDecimal.valueOf((double) defectCount / tasksCompleted).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        profile.setDefectRate(defectRate);

        // 5. Reopen Rate (Total reopens / total completed stories)
        long totalReopens = completedStories.stream()
                .mapToLong(Story::getReopenedCount)
                .sum();
        BigDecimal reopenRate = tasksCompleted > 0
                ? BigDecimal.valueOf((double) totalReopens / tasksCompleted).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        profile.setReopenRate(reopenRate);

        // 6. Blocked Frequency (Blocked stories / total assigned stories)
        long blockedCount = assignedStories.stream()
                .filter(Story::isBlocked)
                .count();
        BigDecimal blockedFreq = !assignedStories.isEmpty()
                ? BigDecimal.valueOf((double) blockedCount / assignedStories.size()).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        profile.setBlockedFrequency(blockedFreq);

        // 7. Completion Variance (Avg cycle time variance in hours vs estimated points)
        double totalVarianceHours = 0;
        int varianceCount = 0;
        for (Story story : completedStories) {
            if (story.getStartedAt() != null && story.getCompletedAt() != null) {
                double actualHours = Duration.between(story.getStartedAt(), story.getCompletedAt()).toMinutes() / 60.0;
                double estimatedHours = story.getStoryPoints() != null ? story.getStoryPoints().doubleValue() * 6.0 : 6.0;
                totalVarianceHours += Math.abs(actualHours - estimatedHours);
                varianceCount++;
            }
        }
        BigDecimal avgVariance = varianceCount > 0
                ? BigDecimal.valueOf(totalVarianceHours / varianceCount).setScale(3, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        profile.setCompletionVariance(avgVariance);

        // 8. Consistency Score (0.0 to 1.0)
        // High stability in story points delivered sprint-over-sprint yields higher consistency.
        if (distinctSprints >= 2) {
            List<Double> pointsList = pointsPerSprint.values().stream()
                    .map(BigDecimal::doubleValue)
                    .toList();
            double mean = pointsList.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = pointsList.stream()
                    .mapToDouble(p -> Math.pow(p - mean, 2))
                    .average().orElse(0.0);
            double stdDev = Math.sqrt(variance);
            double cv = mean > 0 ? stdDev / mean : 1.0;
            double consistency = Math.max(0.1, Math.min(1.0, 1.0 - (0.5 * cv)));
            profile.setConsistencyScore(BigDecimal.valueOf(consistency).setScale(3, RoundingMode.HALF_UP));
        } else if (distinctSprints == 1) {
            profile.setConsistencyScore(BigDecimal.valueOf(0.700).setScale(3, RoundingMode.HALF_UP));
        } else {
            profile.setConsistencyScore(BigDecimal.valueOf(0.500).setScale(3, RoundingMode.HALF_UP));
        }

        // 9. Learning Rate (-0.5000 to +0.5000)
        // Trend in velocity progression across chronological sprints
        if (distinctSprints >= 3) {
            List<Sprint> sprintList = sprintIds.stream()
                    .map(sprintsById::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Sprint::getSequenceNo))
                    .toList();

            if (sprintList.size() >= 3) {
                int half = sprintList.size() / 2;
                double olderSum = 0;
                for (int i = 0; i < half; i++) {
                    olderSum += pointsPerSprint.getOrDefault(sprintList.get(i).getId(), BigDecimal.ZERO).doubleValue();
                }
                double newerSum = 0;
                for (int i = sprintList.size() - half; i < sprintList.size(); i++) {
                    newerSum += pointsPerSprint.getOrDefault(sprintList.get(i).getId(), BigDecimal.ZERO).doubleValue();
                }
                double olderAvg = olderSum / half;
                double newerAvg = newerSum / half;
                double slope = (newerAvg - olderAvg) / (Math.max(1.0, olderAvg) * (sprintList.size() - 1));
                double clampedLearningRate = Math.max(-0.5, Math.min(0.5, slope));
                profile.setLearningRate(BigDecimal.valueOf(clampedLearningRate).setScale(4, RoundingMode.HALF_UP));
            } else {
                profile.setLearningRate(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            }
        } else {
            profile.setLearningRate(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        }

        profile.setLastComputedAt(Instant.now());
        DeveloperProfile saved = profileRepository.save(profile);
        syncToOptimizer(userId, saved);

        log.info("Updated developer profile for userId {}: tasks={}, avgVel={}, defectRate={}, reopenRate={}, consistency={}, learningRate={}",
                userId, tasksCompleted, profile.getAvgVelocity(), profile.getDefectRate(),
                profile.getReopenRate(), profile.getConsistencyScore(), profile.getLearningRate());

        return saved;
    }

    /**
     * Batch recomputation for a set of users (e.g., all sprint contributors).
     */
    @Transactional
    public List<DeveloperProfile> recomputeForUsers(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        return userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(this::recomputeForUser)
                .toList();
    }

    public com.sprintiq.pms.modules.users.web.dto.UserDtos.DeveloperProfileResponse toResponse(DeveloperProfile profile) {
        return new com.sprintiq.pms.modules.users.web.dto.UserDtos.DeveloperProfileResponse(
                profile.getUserId(), profile.getSeniority(),
                profile.getYearsExperience(), profile.getHourlyRate(), profile.getWeeklyCapacityHours(),
                profile.getAvailabilityRate(), profile.getAvgVelocity(), profile.getConsistencyScore(),
                profile.getLearningRate(), profile.getDefectRate(), profile.getReopenRate(),
                profile.getBlockedFrequency(), profile.getCompletionVariance(),
                profile.getTasksCompleted(), profile.hasMeaningfulHistory(),
                profile.getLastComputedAt());
    }

    /**
     * Recomputes all developer profiles for an organization.
     */
    @Transactional
    public List<com.sprintiq.pms.modules.users.web.dto.UserDtos.DeveloperProfileResponse> recomputeForOrganization(UUID organizationId) {
        List<DeveloperProfile> profiles = profileRepository.findByOrganizationId(organizationId);
        List<UUID> userIds = profiles.stream().map(DeveloperProfile::getUserId).toList();
        return recomputeForUsers(userIds).stream().map(this::toResponse).toList();
    }

    /**
     * Dual table synchronization: Syncs the freshly derived metrics into the optimizer tables
     * if the user has a linked OptimizerDeveloper entry.
     */
    private void syncToOptimizer(UUID userId, DeveloperProfile profile) {
        if (optimizerDeveloperRepository == null) {
            return;
        }
        try {
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) return;

            User user = userOpt.get();
            String email = user.getEmail();

            // Find matching Optimizer Developer by email or dev_id
            List<com.sprintiq.pms.modules.ai.domain.Developer> devs = optimizerDeveloperRepository.findAll();
            for (com.sprintiq.pms.modules.ai.domain.Developer dev : devs) {
                boolean match = false;
                if (dev.getProfile() != null && dev.getProfile().getEmail() != null
                        && dev.getProfile().getEmail().equalsIgnoreCase(email)) {
                    match = true;
                } else if (dev.getDev_id() != null && dev.getDev_id().equalsIgnoreCase(user.getEmailNormalized())) {
                    match = true;
                }

                if (match) {
                    dev.setConsistency(profile.getConsistencyScore().doubleValue());
                    dev.setLearning_rate(profile.getLearningRate().doubleValue());
                    if (profile.getAvgVelocity() != null) {
                        dev.setBase_velocity(profile.getAvgVelocity().doubleValue());
                    }
                    optimizerDeveloperRepository.save(dev);
                    log.debug("Synchronized metrics to OptimizerDeveloper id={} for user {}", dev.getId(), userId);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Non-fatal: Failed to sync developer profile to optimizer: {}", e.getMessage());
        }
    }
}
