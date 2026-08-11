package com.sprintiq.pms.modules.users.application;

import com.sprintiq.pms.modules.ai.application.port.DeveloperFeatureSource;
import com.sprintiq.pms.modules.projects.domain.ProjectMemberRepository;
import com.sprintiq.pms.modules.users.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Supplies the Model 3 candidate pool: project members, their profiles and their skills.
 *
 * <p>Stakeholders are excluded — {@code findDeliveryUserIds} is the same query the
 * assignee picker uses, so the model never proposes handing a task to the person who
 * requested it.</p>
 *
 * <p>People without a profile row are still returned, carrying defaults. Dropping them
 * would mean a new joiner is invisible to the allocation panel, which is precisely the
 * person a lead is most likely to be looking for a first task for.</p>
 */
@Component
@RequiredArgsConstructor
public class DeveloperFeatureProvider implements DeveloperFeatureSource {

    /**
     * Fallback blended rate when nobody on the project has one recorded.
     *
     * <p>A cost forecast with no rates is not "free", it is unknown — but returning zero
     * would render a $0 forecast that reads as a fact. A visible, conventional default
     * produces a figure the team can immediately see is wrong and correct, which is the
     * better failure.</p>
     */
    private static final BigDecimal FALLBACK_HOURLY_RATE = BigDecimal.valueOf(65);

    private final ProjectMemberRepository projectMembers;
    private final DeveloperProfileRepository profiles;
    private final DeveloperSkillRepository developerSkills;
    private final UserService userService;

    @Override
    @Transactional(readOnly = true)
    public List<DeveloperFeatures> candidatesFor(UUID projectId) {
        return describe(projectMembers.findDeliveryUserIds(projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeveloperFeatures> candidatesByIds(UUID projectId, List<UUID> userIds) {
        Set<UUID> members = new HashSet<>(projectMembers.findDeliveryUserIds(projectId));
        // Silently narrowing to project members rather than rejecting: a caller passing a
        // stale id should get a shorter ranking, not a failed one.
        return describe(userIds.stream().filter(members::contains).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal averageHourlyRate(UUID projectId) {
        List<UUID> members = projectMembers.findDeliveryUserIds(projectId);
        if (members.isEmpty()) {
            return FALLBACK_HOURLY_RATE;
        }
        BigDecimal average = profiles.averageHourlyRate(members);
        return average == null || average.compareTo(BigDecimal.ZERO) <= 0
                ? FALLBACK_HOURLY_RATE
                : average.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ internals

    private List<DeveloperFeatures> describe(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, DeveloperProfile> profileByUser = new HashMap<>();
        profiles.findByUserIdIn(userIds).forEach(profile -> profileByUser.put(profile.getUserId(), profile));

        Map<UUID, Map<String, Short>> skillsByUser = new HashMap<>();
        for (DeveloperSkillRepository.UserSkillRow row : developerSkills.findSkillsFor(userIds)) {
            skillsByUser.computeIfAbsent(row.getUserId(), key -> new HashMap<>())
                    .put(row.getSkillName().toLowerCase(Locale.ROOT), row.getProficiency());
        }

        Map<UUID, UserSummary> summaries = userService.summariesFor(userIds);

        List<DeveloperFeatures> candidates = new ArrayList<>(userIds.size());
        for (UUID userId : userIds) {
            UserSummary summary = summaries.get(userId);
            if (summary == null) {
                continue;
            }
            DeveloperProfile profile = profileByUser.getOrDefault(userId,
                    DeveloperProfile.builder().userId(userId).build());

            candidates.add(new DeveloperFeatures(userId, summary.fullName(), summary.avatarUrl(),
                    profile.getSeniority().name(), profile.getYearsExperience(), profile.getHourlyRate(),
                    profile.getWeeklyCapacityHours(), profile.getAvailabilityRate(),
                    profile.getAvgVelocity(), profile.getConsistencyScore(), profile.getLearningRate(),
                    profile.getDefectRate(), profile.getReopenRate(), profile.getBlockedFrequency(),
                    profile.getCompletionVariance(), profile.getTasksCompleted(), null,
                    skillsByUser.getOrDefault(userId, Map.of())));
        }
        return candidates;
    }
}
