package com.sprintiq.pms.modules.users.application;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.users.domain.*;
import com.sprintiq.pms.modules.users.web.dto.UserDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Reads and edits developer profiles and skills.
 *
 * <p>Only the declared half is editable here. The derived metrics — velocity,
 * consistency, learning rate, defect and reopen rates — are computed from delivery
 * history and have no write endpoint at all. Letting anyone type their own consistency
 * score would turn the allocation model from a measurement into a negotiation.</p>
 */
@Service
@RequiredArgsConstructor
public class DeveloperProfileService {

    private final DeveloperProfileRepository profiles;
    private final DeveloperSkillRepository developerSkills;
    private final SkillRepository skills;
    private final UserService userService;

    @Transactional(readOnly = true)
    public DeveloperProfileResponse get(UUID userId) {
        userService.requireActiveMember(userId);
        return profiles.findByUserId(userId)
                .map(this::toResponse)
                .orElseGet(() -> defaultsFor(userId));
    }

    /** Creates the profile on first write; there is nothing to see until someone fills it in. */
    @Transactional
    public DeveloperProfileResponse update(UUID userId, UpdateDeveloperProfileRequest request) {
        userService.requireActiveMember(userId);
        DeveloperProfile profile = profiles.findByUserId(userId)
                .orElseGet(() -> DeveloperProfile.builder().userId(userId).build());

        if (request.seniority() != null) profile.setSeniority(request.seniority());
        if (request.yearsExperience() != null) profile.setYearsExperience(request.yearsExperience());
        if (request.hourlyRate() != null) profile.setHourlyRate(request.hourlyRate());
        if (request.weeklyCapacityHours() != null) {
            profile.setWeeklyCapacityHours(request.weeklyCapacityHours());
        }
        if (request.availabilityRate() != null) profile.setAvailabilityRate(request.availabilityRate());

        return toResponse(profiles.save(profile));
    }

    @Transactional(readOnly = true)
    public List<UserSkillResponse> skillsOf(UUID userId) {
        Map<UUID, Skill> byId = new HashMap<>();
        skills.findAllByOrderByNameAsc().forEach(skill -> byId.put(skill.getId(), skill));

        return developerSkills.findByUserId(userId).stream()
                .map(held -> {
                    Skill skill = byId.get(held.getSkillId());
                    return new UserSkillResponse(held.getSkillId(),
                            skill == null ? "(removed)" : skill.getName(),
                            skill == null ? null : skill.getCategory(),
                            held.getProficiency());
                })
                .sorted(Comparator.comparing(UserSkillResponse::skillName))
                .toList();
    }

    /**
     * Replaces a person's whole skill set.
     *
     * <p>PUT rather than PATCH because the UI is a multi-select: removing a skill has to
     * be expressible, and an endpoint that can only add would leave stale capabilities
     * influencing assignment suggestions forever.</p>
     */
    @Transactional
    public List<UserSkillResponse> replaceSkills(UUID userId, UpdateUserSkillsRequest request) {
        userService.requireActiveMember(userId);
        developerSkills.deleteByUserId(userId);

        List<DeveloperSkill> rows = new ArrayList<>(request.skills().size());
        for (UserSkillEntry entry : request.skills()) {
            Skill skill = resolveSkill(entry);
            rows.add(DeveloperSkill.builder()
                    .userId(userId)
                    .skillId(skill.getId())
                    .proficiency(entry.proficiency() == null
                            ? DeveloperSkill.WORKING_PROFICIENCY : entry.proficiency())
                    .build());
        }
        developerSkills.saveAll(rows);
        return skillsOf(userId);
    }

    @Transactional(readOnly = true)
    public List<SkillResponse> catalogue() {
        return skills.findAllByOrderByNameAsc().stream()
                .map(skill -> new SkillResponse(skill.getId(), skill.getName(), skill.getCategory()))
                .toList();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Resolves a skill by id, or creates it by name.
     *
     * <p>Create-on-use because the alternative — an administrator curating the skill list
     * before anyone can record a capability — means nobody records capabilities, and the
     * allocation model falls back to workload alone.</p>
     */
    private Skill resolveSkill(UserSkillEntry entry) {
        if (entry.skillId() != null) {
            return skills.findById(entry.skillId())
                    .orElseThrow(() -> new ResourceNotFoundException("Skill", entry.skillId()));
        }
        String name = entry.skillName().trim();
        return skills.findByNameIgnoreCase(name)
                .orElseGet(() -> skills.save(Skill.builder().name(name).build()));
    }

    private DeveloperProfileResponse toResponse(DeveloperProfile profile) {
        return new DeveloperProfileResponse(profile.getUserId(), profile.getSeniority(),
                profile.getYearsExperience(), profile.getHourlyRate(), profile.getWeeklyCapacityHours(),
                profile.getAvailabilityRate(), profile.getAvgVelocity(), profile.getConsistencyScore(),
                profile.getLearningRate(), profile.getDefectRate(), profile.getReopenRate(),
                profile.getBlockedFrequency(), profile.getCompletionVariance(),
                profile.getTasksCompleted(), profile.hasMeaningfulHistory(),
                profile.getLastComputedAt());
    }

    /** A person with no profile yet still has to render — as defaults, flagged as unmeasured. */
    private DeveloperProfileResponse defaultsFor(UUID userId) {
        DeveloperProfile empty = DeveloperProfile.builder().userId(userId).build();
        return toResponse(empty);
    }
}
