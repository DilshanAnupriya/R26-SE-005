package com.sprintiq.pms.modules.sprints.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.application.ChangeSet;
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.audit.domain.AuditAction;
import com.sprintiq.pms.modules.backlog.application.SprintBacklogService;
import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.projects.application.ProjectLookupService;
import com.sprintiq.pms.modules.projects.application.ProjectRef;
import com.sprintiq.pms.modules.sprints.domain.*;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sprint lifecycle: plan, start, complete, cancel.
 *
 * <p>The two moments that matter are start and complete. Starting freezes the
 * commitment; completing measures delivery against it and decides what happens to work
 * that did not finish. Everything else is editing metadata.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SprintService {

    public static final Set<String> SORT_FIELDS =
            Set.of("sequenceNo", "name", "status", "startDate", "endDate", "createdAt");

    private final SprintRepository sprintRepository;
    private final ProjectLookupService projectLookup;
    private final SprintBacklogService sprintBacklog;
    private final SprintMetricsService metricsService;
    private final ActivityService activityService;
    private final AuditService auditService;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<SprintResponse> search(UUID projectId, SprintStatus status, PageQuery query) {
        if (projectId != null) {
            projectLookup.require(projectId);
        }
        return PageResponse.from(sprintRepository.search(projectId, status, query.searchTerm(),
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "sequenceNo"))), this::toResponse);
    }

    @Transactional(readOnly = true)
    public SprintResponse get(UUID sprintId) {
        return toResponse(require(sprintId));
    }

    @Transactional(readOnly = true)
    public List<StoryResponse> items(UUID sprintId) {
        require(sprintId);
        return sprintBacklog.storiesIn(sprintId);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public SprintResponse create(CreateSprintRequest request) {
        ProjectRef project = projectLookup.requireWritable(request.projectId());
        int sequenceNo = sprintRepository.findMaxSequenceNo(request.projectId()) + 1;

        short duration = request.durationDays() != null
                ? request.durationDays()
                : project.defaultSprintDays();

        LocalDate start = request.startDate();
        LocalDate end = request.endDate();
        // Give the sprint a coherent window even when the client supplies only one end:
        // an unbounded sprint has no ideal burndown line and no cost forecast horizon.
        if (start != null && end == null) {
            end = start.plusDays(duration - 1L);
        } else if (start == null && end != null) {
            start = end.minusDays(duration - 1L);
        }
        if (start != null && end != null && end.isBefore(start)) {
            throw new BusinessRuleException("Sprint end date cannot fall before its start date");
        }

        Sprint sprint = Sprint.builder()
                .projectId(request.projectId())
                .sequenceNo(sequenceNo)
                .name(request.name() == null || request.name().isBlank()
                        ? "Sprint %d".formatted(sequenceNo)
                        : request.name().trim())
                .goal(request.goal())
                .status(SprintStatus.PLANNED)
                .startDate(start)
                .endDate(end)
                .durationDays(start != null && end != null
                        ? (short) (ChronoUnit.DAYS.between(start, end) + 1)
                        : duration)
                .capacityPoints(request.capacityPoints())
                .capacityHours(request.capacityHours())
                .budgetAmount(request.budgetAmount())
                .build();
        sprint.setOrganizationId(TenantContext.requireOrganizationId());
        sprint = sprintRepository.save(sprint);

        activityService.recordCreated(target(sprint), "%s created".formatted(sprint.getName()));
        return toResponse(sprint);
    }

    @Transactional
    public SprintResponse update(UUID sprintId, UpdateSprintRequest request) {
        Sprint sprint = require(sprintId);
        if (sprint.getStatus().isTerminal()) {
            throw new ConflictException("%s is %s and can no longer be edited"
                    .formatted(sprint.getName(), sprint.getStatus()));
        }
        ChangeSet changes = new ChangeSet();

        changes.apply("name", sprint.getName(), trim(request.name()), sprint::setName);
        changes.apply("goal", sprint.getGoal(), request.goal(), sprint::setGoal);
        changes.apply("startDate", sprint.getStartDate(), request.startDate(), sprint::setStartDate);
        changes.apply("endDate", sprint.getEndDate(), request.endDate(), sprint::setEndDate);
        changes.apply("capacityPoints", sprint.getCapacityPoints(), request.capacityPoints(),
                sprint::setCapacityPoints);
        changes.apply("capacityHours", sprint.getCapacityHours(), request.capacityHours(),
                sprint::setCapacityHours);
        changes.apply("budgetAmount", sprint.getBudgetAmount(), request.budgetAmount(),
                sprint::setBudgetAmount);
        changes.apply("plannedCostAmount", sprint.getPlannedCostAmount(), request.plannedCostAmount(),
                sprint::setPlannedCostAmount);

        if (sprint.getStartDate() != null && sprint.getEndDate() != null
                && sprint.getEndDate().isBefore(sprint.getStartDate())) {
            throw new BusinessRuleException("Sprint end date cannot fall before its start date");
        }
        if (changes.isEmpty()) {
            return toResponse(sprint);
        }
        if (sprint.getStartDate() != null && sprint.getEndDate() != null) {
            sprint.setDurationDays(
                    (short) (ChronoUnit.DAYS.between(sprint.getStartDate(), sprint.getEndDate()) + 1));
        }
        sprint = sprintRepository.save(sprint);
        activityService.recordChanges(target(sprint), changes);
        return toResponse(sprint);
    }

    /**
     * Starts the sprint and freezes its commitment.
     *
     * <p>{@code committedPoints} is captured here, once, from whatever is in the sprint
     * at this moment. Everything the sprint is judged on afterwards — completion rate,
     * velocity, over-budget risk — is measured against this number, so it must be taken
     * before the first day of work and never recalculated.</p>
     */
    @Transactional
    public SprintResponse start(UUID sprintId) {
        Sprint sprint = require(sprintId);
        assertTransition(sprint, SprintStatus.ACTIVE);

        sprintRepository.findByProjectIdAndStatusAndDeletedAtIsNull(sprint.getProjectId(), SprintStatus.ACTIVE)
                .ifPresent(active -> {
                    throw new ConflictException(
                            "%s is already active in this project; complete it before starting another"
                                    .formatted(active.getName()));
                });

        SprintBacklogService.SprintPoints points = sprintBacklog.pointsIn(sprintId);
        if (points.storyCount() == 0) {
            throw new BusinessRuleException(
                    "%s has no items; add work before starting it".formatted(sprint.getName()));
        }

        sprint.setCommittedPoints(points.committed());
        sprint.setStatus(SprintStatus.ACTIVE);
        sprint.setStartedAt(Instant.now());
        if (sprint.getStartDate() == null) {
            sprint.setStartDate(LocalDate.now());
        }
        if (sprint.getEndDate() == null && sprint.getDurationDays() != null) {
            sprint.setEndDate(sprint.getStartDate().plusDays(sprint.getDurationDays() - 1L));
        }
        sprint = sprintRepository.save(sprint);

        metricsService.snapshot(sprint, LocalDate.now());
        activityService.record(target(sprint), ActivityAction.SPRINT_STARTED,
                "%s started with %s committed point(s) across %d item(s)"
                        .formatted(sprint.getName(), points.committed(), points.storyCount()));
        auditService.record(AuditAction.SPRINT_STARTED, "Sprint", sprintId, null,
                Map.of("name", sprint.getName(), "committedPoints", points.committed(),
                        "storyCount", points.storyCount()));
        return toResponse(sprint);
    }

    /**
     * Closes the sprint, records what was delivered and moves what was not.
     *
     * <p>Unfinished work is never left pointing at a completed sprint: that would make
     * it invisible in both the backlog and the next sprint, which is how items quietly
     * disappear for a quarter.</p>
     */
    @Transactional
    public CompleteSprintResponse complete(UUID sprintId, CompleteSprintRequest request) {
        Sprint sprint = require(sprintId);
        assertTransition(sprint, SprintStatus.COMPLETED);

        SprintBacklogService.SprintPoints points = sprintBacklog.pointsIn(sprintId);
        UUID destination = resolveSpilloverTarget(sprint, request);

        List<Story> spilled = sprintBacklog.spillOver(sprintId, destination);

        sprint.setCompletedPoints(points.completed());
        sprint.setStatus(SprintStatus.COMPLETED);
        sprint.setCompletedAt(Instant.now());
        if (sprint.getEndDate() == null) {
            sprint.setEndDate(LocalDate.now());
        }
        sprint = sprintRepository.save(sprint);

        metricsService.snapshot(sprint, LocalDate.now());
        activityService.record(target(sprint), ActivityAction.SPRINT_COMPLETED,
                "%s completed: %s of %s point(s) delivered, %d item(s) carried over"
                        .formatted(sprint.getName(), points.completed(), sprint.getCommittedPoints(),
                                spilled.size()));
        auditService.record(AuditAction.SPRINT_COMPLETED, "Sprint", sprintId, null,
                Map.of("committedPoints", sprint.getCommittedPoints(),
                        "completedPoints", points.completed(),
                        "spilloverCount", spilled.size(),
                        "spilloverStrategy", request.spilloverStrategy().name()));

        return new CompleteSprintResponse(toResponse(sprint), (int) points.completedCount(), spilled.size(),
                destination, points.completed());
    }

    /**
     * Cancels a sprint. Everything in it returns to the backlog — a cancelled sprint
     * makes no claim about delivery, so its items should not carry its number.
     */
    @Transactional
    public SprintResponse cancel(UUID sprintId) {
        Sprint sprint = require(sprintId);
        assertTransition(sprint, SprintStatus.CANCELLED);

        List<Story> returned = sprintBacklog.spillOver(sprintId, null);
        sprint.setStatus(SprintStatus.CANCELLED);
        sprint.setCompletedAt(Instant.now());
        sprint = sprintRepository.save(sprint);

        activityService.record(target(sprint), ActivityAction.SPRINT_CANCELLED,
                "%s cancelled; %d item(s) returned to the backlog"
                        .formatted(sprint.getName(), returned.size()));
        auditService.record(AuditAction.SPRINT_CANCELLED, "Sprint", sprintId, null,
                Map.of("name", sprint.getName(), "returnedItems", returned.size()));
        return toResponse(sprint);
    }

    @Transactional
    public void delete(UUID sprintId) {
        Sprint sprint = require(sprintId);
        if (sprint.getStatus() == SprintStatus.ACTIVE) {
            throw new ConflictException("Cancel %s before deleting it".formatted(sprint.getName()));
        }
        sprintBacklog.spillOver(sprintId, null);
        sprint.setDeletedAt(Instant.now());
        sprintRepository.save(sprint);
        activityService.recordDeleted(target(sprint), "%s deleted".formatted(sprint.getName()));
    }

    // ------------------------------------------------------------------- items

    @Transactional
    public List<StoryResponse> addItems(UUID sprintId, AddSprintItemsRequest request) {
        Sprint sprint = require(sprintId);
        if (sprint.getStatus().isTerminal()) {
            throw new ConflictException("%s is %s; items cannot be added"
                    .formatted(sprint.getName(), sprint.getStatus()));
        }

        BigDecimal before = sprintBacklog.pointsIn(sprintId).committed();
        List<StoryResponse> added = sprintBacklog.addToSprint(sprintId, sprint.getProjectId(),
                request.storyIds());
        BigDecimal after = sprintBacklog.pointsIn(sprintId).committed();

        // Scope that arrives after the sprint started is churn, and the M2 model reads it.
        sprint.recordScopeChange(after.subtract(before), true);
        sprintRepository.save(sprint);
        return added;
    }

    @Transactional
    public void removeItem(UUID sprintId, UUID storyId) {
        Sprint sprint = require(sprintId);
        BigDecimal before = sprintBacklog.pointsIn(sprintId).committed();
        sprintBacklog.removeFromSprint(sprintId, storyId);
        BigDecimal after = sprintBacklog.pointsIn(sprintId).committed();

        sprint.recordScopeChange(before.subtract(after), false);
        sprintRepository.save(sprint);
    }

    // ------------------------------------------------------------------ internals

    Sprint require(UUID sprintId) {
        return sprintRepository.findByIdAndDeletedAtIsNull(sprintId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint", sprintId));
    }

    private void assertTransition(Sprint sprint, SprintStatus target) {
        if (!sprint.getStatus().canTransitionTo(target)) {
            throw ConflictException.illegalTransition("Sprint", sprint.getStatus().name(), target.name());
        }
    }

    /**
     * Where unfinished work goes. {@code NEXT_SPRINT} creates the next sprint if the
     * team has not planned one yet — the alternative is refusing to close the sprint,
     * which helps nobody at the end of a review meeting.
     */
    private UUID resolveSpilloverTarget(Sprint sprint, CompleteSprintRequest request) {
        return switch (request.spilloverStrategy()) {
            case BACKLOG -> null;
            case SPECIFIC_SPRINT -> {
                if (request.targetSprintId() == null) {
                    throw new BusinessRuleException(
                            "targetSprintId is required when spilloverStrategy is SPECIFIC_SPRINT");
                }
                Sprint destination = require(request.targetSprintId());
                if (!destination.getProjectId().equals(sprint.getProjectId())) {
                    throw new ConflictException("The target sprint belongs to a different project");
                }
                if (destination.getStatus().isTerminal()) {
                    throw new ConflictException("Cannot carry work into a %s sprint"
                            .formatted(destination.getStatus()));
                }
                yield destination.getId();
            }
            case NEXT_SPRINT -> sprintRepository
                    .findFirstByProjectIdAndStatusAndDeletedAtIsNullOrderBySequenceNoAsc(
                            sprint.getProjectId(), SprintStatus.PLANNED)
                    .map(Sprint::getId)
                    .orElseGet(() -> createFollowOn(sprint).getId());
        };
    }

    private Sprint createFollowOn(Sprint previous) {
        int sequenceNo = sprintRepository.findMaxSequenceNo(previous.getProjectId()) + 1;
        short duration = previous.getDurationDays() == null ? 14 : previous.getDurationDays();
        LocalDate start = previous.getEndDate() == null ? LocalDate.now() : previous.getEndDate().plusDays(1);

        Sprint next = Sprint.builder()
                .projectId(previous.getProjectId())
                .sequenceNo(sequenceNo)
                .name("Sprint %d".formatted(sequenceNo))
                .status(SprintStatus.PLANNED)
                .startDate(start)
                .endDate(start.plusDays(duration - 1L))
                .durationDays(duration)
                .capacityPoints(previous.getCapacityPoints())
                .capacityHours(previous.getCapacityHours())
                .build();
        next.setOrganizationId(TenantContext.requireOrganizationId());
        next = sprintRepository.save(next);
        activityService.recordCreated(target(next),
                "%s created automatically to receive spillover".formatted(next.getName()));
        return next;
    }

    private ActivityService.Target target(Sprint sprint) {
        return ActivityService.Target.of(EntityType.SPRINT, sprint.getId(), sprint.getName(),
                sprint.getProjectId());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    SprintResponse toResponse(Sprint sprint) {
        SprintBacklogService.SprintPoints points = sprintBacklog.pointsIn(sprint.getId());
        Integer daysRemaining = sprint.getEndDate() == null || sprint.getStatus().isTerminal()
                ? null
                : (int) ChronoUnit.DAYS.between(LocalDate.now(), sprint.getEndDate());

        return new SprintResponse(sprint.getId(), sprint.getProjectId(), sprint.getSequenceNo(),
                sprint.getName(), sprint.getGoal(), sprint.getStatus(), sprint.getStartDate(),
                sprint.getEndDate(), sprint.getDurationDays(), sprint.getTeamSize(),
                sprint.getCapacityPoints(), sprint.getCapacityHours(), sprint.getCommittedPoints(),
                // While the sprint runs, "completed" is whatever is done right now; once it
                // closes, the stored figure is authoritative and must not drift afterwards.
                sprint.getStatus().isTerminal() ? sprint.getCompletedPoints() : points.completed(),
                sprint.getAddedPoints(), sprint.getRemovedPoints(), sprint.completionRate(),
                sprint.getPlannedCostAmount(), sprint.getForecastCostAmount(), sprint.getBudgetAmount(),
                points.storyCount(), points.completedCount(), daysRemaining,
                sprint.getStartedAt(), sprint.getCompletedAt(), sprint.getCreatedAt());
    }
}
