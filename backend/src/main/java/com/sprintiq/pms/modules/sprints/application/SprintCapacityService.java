package com.sprintiq.pms.modules.sprints.application;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.backlog.application.SprintBacklogService;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintCapacity;
import com.sprintiq.pms.modules.sprints.domain.SprintCapacityRepository;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.*;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-member availability for a sprint, and the capacity meter built from it.
 *
 * <p>The meter answers the question sprint planning actually turns on: is what we have
 * just taken on more than we can do? It compares planned points against the sprint's
 * point capacity, and shows the hours behind that figure so an over-commitment can be
 * traced to a specific person's availability rather than argued about in the abstract.</p>
 */
@Service
@RequiredArgsConstructor
public class SprintCapacityService {

    /** Working day used to convert absence days into hours. */
    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(8);

    private final SprintCapacityRepository capacityRepository;
    private final SprintRepository sprintRepository;
    private final SprintBacklogService sprintBacklog;
    private final UserService userService;

    @Transactional(readOnly = true)
    public SprintCapacityResponse get(UUID sprintId) {
        Sprint sprint = requireSprint(sprintId);
        return build(sprint, capacityRepository.findBySprintId(sprintId));
    }

    /**
     * Replaces the whole capacity plan for a sprint.
     *
     * <p>Declarative rather than incremental: the planning UI edits a grid and knows the
     * complete intended state, and applying it wholesale means a member removed from the
     * grid actually disappears instead of lingering as a stale row that still counts
     * toward capacity.</p>
     */
    @Transactional
    public SprintCapacityResponse update(UUID sprintId, UpdateCapacityRequest request) {
        Sprint sprint = requireSprint(sprintId);
        UUID organizationId = TenantContext.requireOrganizationId();

        List<SprintCapacity> existing = capacityRepository.findBySprintId(sprintId);
        Map<UUID, SprintCapacity> byUser = existing.stream()
                .collect(java.util.stream.Collectors.toMap(SprintCapacity::getUserId, entry -> entry));

        for (SprintCapacityEntry entry : request.entries()) {
            userService.requireActiveMember(entry.userId());

            SprintCapacity capacity = byUser.remove(entry.userId());
            if (capacity == null) {
                capacity = SprintCapacity.builder()
                        .sprintId(sprintId)
                        .userId(entry.userId())
                        .build();
                capacity.setOrganizationId(organizationId);
            }
            capacity.setAvailableHours(orZero(entry.availableHours()));
            capacity.setAbsenceDays(orZero(entry.absenceDays()));
            capacity.setAllocationPct(entry.allocationPct() == null ? 100 : entry.allocationPct());
            capacity.setCommittedPoints(orZero(entry.committedPoints()));
            capacityRepository.save(capacity);
        }

        // Anything still in the map was not in the submitted grid, so it was removed.
        byUser.values().forEach(capacityRepository::delete);

        List<SprintCapacity> refreshed = capacityRepository.findBySprintId(sprintId);
        BigDecimal totalAbsence = refreshed.stream()
                .map(SprintCapacity::getAbsenceDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // The sprint carries the totals its own AI features and cost forecast read.
        sprint.setTeamSize((short) refreshed.size());
        sprint.setAbsenceDays(totalAbsence);
        sprint.setCapacityHours(refreshed.stream()
                .map(entry -> entry.effectiveHours(HOURS_PER_DAY))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        sprintRepository.save(sprint);

        return build(sprint, refreshed);
    }

    // ------------------------------------------------------------------ internals

    private SprintCapacityResponse build(Sprint sprint, List<SprintCapacity> entries) {
        Map<UUID, UserSummary> profiles = userService.summariesFor(
                entries.stream().map(SprintCapacity::getUserId).toList());

        List<CapacityMemberResponse> members = entries.stream()
                .map(entry -> {
                    UserSummary profile = profiles.get(entry.getUserId());
                    return new CapacityMemberResponse(entry.getUserId(),
                            profile == null ? null : profile.fullName(),
                            profile == null ? null : profile.avatarUrl(),
                            entry.getAvailableHours(), entry.getAbsenceDays(), entry.getAllocationPct(),
                            entry.effectiveHours(HOURS_PER_DAY), entry.getCommittedPoints());
                })
                .toList();

        BigDecimal totalAvailable = entries.stream()
                .map(SprintCapacity::getAvailableHours).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEffective = entries.stream()
                .map(entry -> entry.effectiveHours(HOURS_PER_DAY)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal plannedPoints = sprintBacklog.pointsIn(sprint.getId()).committed();
        BigDecimal capacityPoints = sprint.getCapacityPoints();

        BigDecimal utilisation = BigDecimal.ZERO;
        boolean overCommitted = false;
        if (capacityPoints != null && capacityPoints.compareTo(BigDecimal.ZERO) > 0) {
            utilisation = plannedPoints.multiply(BigDecimal.valueOf(100))
                    .divide(capacityPoints, 2, RoundingMode.HALF_UP);
            overCommitted = plannedPoints.compareTo(capacityPoints) > 0;
        }

        return new SprintCapacityResponse(sprint.getId(), totalAvailable, totalEffective, capacityPoints,
                plannedPoints, utilisation, overCommitted, members);
    }

    private Sprint requireSprint(UUID sprintId) {
        return sprintRepository.findByIdAndDeletedAtIsNull(sprintId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint", sprintId));
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
