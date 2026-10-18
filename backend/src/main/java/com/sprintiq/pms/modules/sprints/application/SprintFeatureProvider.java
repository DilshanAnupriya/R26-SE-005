package com.sprintiq.pms.modules.sprints.application;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.backlog.application.SprintBacklogService;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Describes sprints to the AI module.
 *
 * <p>Feeds M1's velocity predictor, both M2 classifiers and both M4 forecasters. The
 * sprint row alone is not enough — points live on stories — so this pulls the backlog
 * snapshot through the module's published {@code SprintBacklogService} rather than
 * reaching into the stories table.</p>
 */
@Component
@RequiredArgsConstructor
public class SprintFeatureProvider implements SprintFeatureSource {

    /**
     * Sprints of history handed to the velocity model.
     *
     * <p>Six is roughly a quarter. Longer windows describe a team that has since changed
     * composition; shorter ones cannot tell a trend from a bad fortnight.</p>
     */
    private static final int HISTORY_WINDOW = 6;

    private final SprintRepository sprintRepository;
    private final com.sprintiq.pms.modules.sprints.domain.SprintCapacityRepository capacityRepository;
    private final SprintBacklogService sprintBacklog;
    private final SprintMetricsService metricsService;

    @Override
    @Transactional(readOnly = true)
    public SprintFeatures featuresFor(UUID sprintId) {
        Sprint sprint = sprintRepository.findByIdAndDeletedAtIsNull(sprintId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint", sprintId));
        SprintBacklogService.SprintSnapshot snapshot = sprintBacklog.snapshotOf(sprintId);

        List<BigDecimal> history = sprintRepository
                .findCompletedHistory(sprint.getProjectId(), PageRequest.of(0, HISTORY_WINDOW))
                .stream()
                .map(Sprint::getCompletedPoints)
                .toList();
        // findCompletedHistory returns newest first; the velocity model weights by
        // recency using position, so it must arrive oldest first.
        List<BigDecimal> chronological = new java.util.ArrayList<>(history);
        java.util.Collections.reverse(chronological);

        Integer elapsed = daysElapsed(sprint);
        Integer remaining = daysRemaining(sprint);

        // A commitment that has been frozen wins; before the freeze, the current contents
        // are the commitment. Using the frozen zero for a sprint still being planned would
        // make every ratio in the risk model infinite.
        BigDecimal committed = sprint.getCommittedPoints().compareTo(BigDecimal.ZERO) > 0
                ? sprint.getCommittedPoints()
                : snapshot.committed();

        return new SprintFeatures(sprint.getId(), sprint.getProjectId(), sprint.getName(),
                sprint.getGoal(), sprint.getSequenceNo(), sprint.getStatus().name(),
                sprint.getCompletedAt(),
                sprint.getStartDate(), sprint.getEndDate(),
                sprint.getDurationDays() == null ? null : (int) sprint.getDurationDays(),
                elapsed, remaining, sprint.getTeamSize(), sprint.getCapacityPoints(),
                sprint.getCapacityHours(), committed, snapshot.completed(), sprint.getAddedPoints(),
                sprint.getRemovedPoints(), sprint.getAbsenceDays(), sprint.getOvertimeHours(),
                sprint.getTotalEffortMinutes(), sprint.getPlannedCostAmount(), sprint.getBudgetAmount(),
                snapshot.storyCount(), snapshot.completedCount(), snapshot.blockedCount(),
                snapshot.defectCount(), snapshot.reopenedCount(), snapshot.unestimatedCount(),
                snapshot.contributorCount(),
                metricsService.rollingVelocity(sprint.getProjectId()), chronological,
                sprintBacklog.carriedOverIn(sprintId, startInstant(sprint)),
                capacityRepository.existsBySprintId(sprintId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> recentSprintIds(UUID projectId, int limit) {
        return sprintRepository.findByProjectIdAndDeletedAtIsNullOrderBySequenceNoDesc(projectId).stream()
                .limit(limit)
                .map(Sprint::getId)
                .toList();
    }


    /**
     * The sprint's start as an instant, for comparing against story start times.
     *
     * <p>Midnight in the system zone rather than UTC: a sprint starting "on the 3rd"
     * means the team's third, and a story begun at 9am local on the 3rd is not
     * carried-over work from the 2nd.</p>
     */
    private java.time.Instant startInstant(Sprint sprint) {
        return sprint.getStartDate() == null
                ? null
                : sprint.getStartDate().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
    }

    // ------------------------------------------------------------------ internals

    private Integer daysElapsed(Sprint sprint) {
        if (sprint.getStartDate() == null) {
            return null;
        }
        long elapsed = ChronoUnit.DAYS.between(sprint.getStartDate(), LocalDate.now());
        if (elapsed < 0) {
            return 0;
        }
        if (sprint.getEndDate() != null) {
            long total = ChronoUnit.DAYS.between(sprint.getStartDate(), sprint.getEndDate());
            return (int) Math.min(elapsed, Math.max(total, 0));
        }
        return (int) elapsed;
    }

    private Integer daysRemaining(Sprint sprint) {
        if (sprint.getEndDate() == null) {
            return null;
        }
        return (int) Math.max(ChronoUnit.DAYS.between(LocalDate.now(), sprint.getEndDate()), 0);
    }

    /**
     * Every ACTIVE sprint with the tenant that owns it.
     *
     * <p>Runs unfiltered so the scheduled watch can see all organizations at once; the
     * organization id travels with each row so every write it triggers is still scoped
     * to one tenant.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<ActiveSprint> activeSprints() {
        return sprintRepository.findByStatusAndDeletedAtIsNull(SprintStatus.ACTIVE).stream()
                .map(sprint -> new ActiveSprint(
                        sprint.getId(), sprint.getOrganizationId(), sprint.getProjectId()))
                .toList();
    }
}
