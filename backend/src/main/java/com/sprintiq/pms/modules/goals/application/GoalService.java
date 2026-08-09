package com.sprintiq.pms.modules.goals.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.goals.domain.*;
import com.sprintiq.pms.modules.goals.web.dto.GoalDtos.*;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Objectives and key results.
 *
 * <p>The service owns one rule above all others: a goal's progress is never set
 * directly. It is always derived from its key results, and recomputed on every path
 * that can move one. An OKR whose headline number can be typed in by hand is a
 * status report, not a measurement.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalService {

    public static final Set<String> SORT_FIELDS =
            Set.of("title", "period", "status", "level", "progressPct", "targetDate", "createdAt", "updatedAt");

    private final GoalRepository goalRepository;
    private final KeyResultRepository keyResultRepository;
    private final KeyResultCheckinRepository checkinRepository;
    private final KeyResultLinkRepository linkRepository;
    private final UserService userService;
    private final AuditService auditService;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<GoalResponse> search(String period, GoalStatus status, GoalLevel level,
                                             UUID projectId, UUID ownerId, PageQuery query) {
        Page<Goal> page = goalRepository.search(period, status, level, projectId, ownerId,
                query.searchTerm(),
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.DESC, "updatedAt")));

        // Key results and owners resolved for the whole page in two queries rather
        // than two per goal — a quarter's OKRs is routinely thirty rows.
        List<UUID> goalIds = page.getContent().stream().map(Goal::getId).toList();
        Map<UUID, List<KeyResult>> resultsByGoal = goalIds.isEmpty()
                ? Map.of()
                : keyResultRepository.findByGoalIdInAndDeletedAtIsNull(goalIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(KeyResult::getGoalId));
        Map<UUID, UserSummary> owners = ownersFor(page.getContent(), resultsByGoal);

        return PageResponse.from(page, goal ->
                toResponse(goal, resultsByGoal.getOrDefault(goal.getId(), List.of()), owners));
    }

    @Transactional(readOnly = true)
    public GoalResponse get(UUID goalId) {
        Goal goal = require(goalId);
        List<KeyResult> results = keyResultRepository
                .findByGoalIdAndDeletedAtIsNullOrderByPositionAsc(goalId);
        return toResponse(goal, results, ownersFor(List.of(goal), Map.of(goalId, results)));
    }

    /** Header figures for the Goals screen. */
    @Transactional(readOnly = true)
    public GoalSummaryResponse summary(String period) {
        PageQuery all = new PageQuery();
        all.setSize(PageQuery.MAX_PAGE_SIZE);
        Page<Goal> page = goalRepository.search(period, null, null, null, null, null,
                all.toPageable(SORT_FIELDS, Sort.by("createdAt")));
        List<Goal> goals = page.getContent();

        BigDecimal average = goals.isEmpty()
                ? BigDecimal.ZERO
                : goals.stream().map(Goal::getProgressPct).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(goals.size()), 2, RoundingMode.HALF_UP);

        return new GoalSummaryResponse(
                period,
                goals.size(),
                count(goals, GoalStatus.ON_TRACK),
                count(goals, GoalStatus.AT_RISK),
                count(goals, GoalStatus.OFF_TRACK),
                count(goals, GoalStatus.ACHIEVED),
                average,
                goalRepository.findPeriods());
    }

    @Transactional(readOnly = true)
    public List<CheckinResponse> history(UUID keyResultId) {
        requireKeyResult(keyResultId);
        List<KeyResultCheckin> checkins = checkinRepository.findHistory(keyResultId);
        Map<UUID, UserSummary> people = userService.summariesFor(
                checkins.stream().map(KeyResultCheckin::getRecordedBy).filter(Objects::nonNull).toList());
        return checkins.stream().map(checkin -> toResponse(checkin, people)).toList();
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public GoalResponse create(CreateGoalRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();

        if (request.startDate() != null && request.targetDate() != null
                && request.targetDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("A goal's target date cannot fall before its start date");
        }
        if (request.ownerUserId() != null) {
            userService.requireActiveMember(request.ownerUserId());
        }

        GoalLevel level = request.level() == null ? GoalLevel.TEAM : request.level();
        if (request.parentGoalId() != null) {
            assertLadderIsValid(request.parentGoalId(), level, null);
        }

        Goal goal = Goal.builder()
                .workspaceId(request.workspaceId())
                .projectId(request.projectId())
                .parentGoalId(request.parentGoalId())
                .title(request.title().trim())
                .description(request.description())
                .period(request.period())
                .startDate(request.startDate())
                .targetDate(request.targetDate())
                .level(level)
                .status(request.status() == null ? GoalStatus.ON_TRACK : request.status())
                .ownerUserId(request.ownerUserId())
                .build();
        goal.setOrganizationId(organizationId);
        goal = goalRepository.save(goal);

        List<KeyResult> created = new ArrayList<>();
        if (request.keyResults() != null) {
            short position = 0;
            for (CreateKeyResultRequest keyResult : request.keyResults()) {
                created.add(persistKeyResult(goal.getId(), keyResult, position++, organizationId));
            }
        }

        goal.recalculateFrom(created);
        goal = goalRepository.save(goal);

        auditService.record("GOAL_CREATED", "Goal", goal.getId(), null,
                Map.of("title", goal.getTitle(), "period", String.valueOf(goal.getPeriod()),
                        "keyResults", created.size()));
        return toResponse(goal, created, ownersFor(List.of(goal), Map.of(goal.getId(), created)));
    }

    @Transactional
    public GoalResponse update(UUID goalId, UpdateGoalRequest request) {
        Goal goal = require(goalId);

        if (request.title() != null) goal.setTitle(request.title().trim());
        if (request.description() != null) goal.setDescription(request.description());
        if (request.period() != null) goal.setPeriod(request.period());
        if (request.startDate() != null) goal.setStartDate(request.startDate());
        if (request.targetDate() != null) goal.setTargetDate(request.targetDate());
        if (request.level() != null) goal.setLevel(request.level());
        if (request.ownerUserId() != null) {
            userService.requireActiveMember(request.ownerUserId());
            goal.setOwnerUserId(request.ownerUserId());
        }
        if (request.parentGoalId() != null) {
            assertLadderIsValid(request.parentGoalId(), goal.getLevel(), goalId);
            goal.setParentGoalId(request.parentGoalId());
        }
        // Status is settable — closing a cycle is a human judgement — but only
        // deliberately. Everything else derives it.
        if (request.status() != null) goal.setStatus(request.status());

        if (goal.getStartDate() != null && goal.getTargetDate() != null
                && goal.getTargetDate().isBefore(goal.getStartDate())) {
            throw new BusinessRuleException("A goal's target date cannot fall before its start date");
        }

        List<KeyResult> results = keyResultRepository
                .findByGoalIdAndDeletedAtIsNullOrderByPositionAsc(goalId);
        goal.recalculateFrom(results);
        goal = goalRepository.save(goal);

        return toResponse(goal, results, ownersFor(List.of(goal), Map.of(goalId, results)));
    }

    @Transactional
    public void delete(UUID goalId) {
        Goal goal = require(goalId);

        // Children are re-parented to this goal's parent rather than orphaned or
        // cascaded: deleting a team objective should not silently destroy the
        // individual goals laddering into it, nor leave them pointing at nothing.
        List<Goal> children = goalRepository.findByParentGoalIdAndDeletedAtIsNull(goalId);
        children.forEach(child -> child.setParentGoalId(goal.getParentGoalId()));
        goalRepository.saveAll(children);

        goal.setDeletedAt(Instant.now());
        goalRepository.save(goal);
        auditService.record("GOAL_DELETED", "Goal", goalId,
                Map.of("title", goal.getTitle()), null);
    }

    @Transactional
    public KeyResultResponse addKeyResult(UUID goalId, CreateKeyResultRequest request) {
        Goal goal = require(goalId);
        short position = (short) keyResultRepository.countByGoalIdAndDeletedAtIsNull(goalId);
        KeyResult keyResult = persistKeyResult(goalId, request, position,
                TenantContext.requireOrganizationId());

        recalculate(goal);
        return toResponse(keyResult, ownersFor(List.of(goal), Map.of(goalId, List.of(keyResult))));
    }

    @Transactional
    public KeyResultResponse updateKeyResult(UUID keyResultId, UpdateKeyResultRequest request) {
        KeyResult keyResult = requireKeyResult(keyResultId);

        if (request.title() != null) keyResult.setTitle(request.title().trim());
        if (request.metricType() != null) keyResult.setMetricType(request.metricType());
        if (request.unit() != null) keyResult.setUnit(request.unit());
        if (request.startValue() != null) keyResult.setStartValue(request.startValue());
        if (request.targetValue() != null) keyResult.setTargetValue(request.targetValue());
        if (request.direction() != null) keyResult.setDirection(request.direction());
        if (request.position() != null) keyResult.setPosition(request.position());
        if (request.ownerUserId() != null) {
            userService.requireActiveMember(request.ownerUserId());
            keyResult.setOwnerUserId(request.ownerUserId());
        }

        if (keyResult.getTargetValue().compareTo(keyResult.getStartValue()) == 0) {
            throw new BusinessRuleException(
                    "A key result's target must differ from its starting value, or there is nothing to measure");
        }

        // Re-derive status against the possibly-changed target before saving.
        Goal parent = require(keyResult.getGoalId());
        keyResult.refreshStatus(parent.expectedProgressPct(LocalDate.now()));
        keyResult = keyResultRepository.save(keyResult);
        recalculate(parent);

        return toResponse(keyResult, Map.of());
    }

    /**
     * Records a measurement.
     *
     * <p>Writes the check-in, moves the key result, then rolls the goal up — in that
     * order, in one transaction, so history and headline can never disagree.</p>
     */
    @Transactional
    public CheckinResponse checkIn(UUID keyResultId, CheckinRequest request) {
        KeyResult keyResult = requireKeyResult(keyResultId);
        BigDecimal previous = keyResult.getCurrentValue();

        KeyResultCheckin checkin = KeyResultCheckin.builder()
                .keyResultId(keyResultId)
                .value(request.value())
                .previousValue(previous)
                .confidence(request.confidence())
                .note(request.note())
                .recordedBy(TenantContext.userId().orElse(null))
                .recordedAt(Instant.now())
                .build();
        // organizationId is inherited, so it is set rather than built — Lombok's
        // @Builder does not cover a superclass's fields.
        checkin.setOrganizationId(TenantContext.requireOrganizationId());
        checkin = checkinRepository.save(checkin);

        Goal goal = require(keyResult.getGoalId());
        keyResult.recordValue(request.value(), goal.expectedProgressPct(LocalDate.now()));
        keyResultRepository.save(keyResult);
        recalculate(goal);

        UUID actor = checkin.getRecordedBy();
        Map<UUID, UserSummary> people =
                actor == null ? Map.of() : userService.summariesFor(List.of(actor));
        return toResponse(checkin, people);
    }

    @Transactional
    public void deleteKeyResult(UUID keyResultId) {
        KeyResult keyResult = requireKeyResult(keyResultId);
        keyResult.setDeletedAt(Instant.now());
        keyResultRepository.save(keyResult);
        recalculate(require(keyResult.getGoalId()));
    }

    @Transactional
    public void linkItem(UUID keyResultId, LinkItemRequest request) {
        requireKeyResult(keyResultId);
        if (linkRepository.existsByKeyResultIdAndEntityTypeAndEntityId(
                keyResultId, request.entityType(), request.entityId())) {
            throw new ConflictException("That item is already linked to this key result");
        }
        KeyResultLink link = KeyResultLink.builder()
                .keyResultId(keyResultId)
                .entityType(request.entityType())
                .entityId(request.entityId())
                .build();
        link.setOrganizationId(TenantContext.requireOrganizationId());
        linkRepository.save(link);
    }

    @Transactional
    public void unlinkItem(UUID keyResultId, EntityType entityType, UUID entityId) {
        requireKeyResult(keyResultId);
        linkRepository.deleteByKeyResultIdAndEntityTypeAndEntityId(keyResultId, entityType, entityId);
    }

    // ------------------------------------------------------------------ internals

    private Goal require(UUID goalId) {
        return goalRepository.findByIdAndDeletedAtIsNull(goalId)
                .orElseThrow(() -> new ResourceNotFoundException("Goal", goalId));
    }

    private KeyResult requireKeyResult(UUID keyResultId) {
        return keyResultRepository.findByIdAndDeletedAtIsNull(keyResultId)
                .orElseThrow(() -> new ResourceNotFoundException("Key result", keyResultId));
    }

    private void recalculate(Goal goal) {
        goal.recalculateFrom(
                keyResultRepository.findByGoalIdAndDeletedAtIsNullOrderByPositionAsc(goal.getId()));
        goalRepository.save(goal);
    }

    /**
     * A goal may only ladder up to a broader one, and never into a cycle.
     *
     * <p>The cycle walk is bounded by a hop limit as well as by the visited set —
     * belt and braces, because a malformed chain in the data would otherwise spin
     * here rather than fail.</p>
     */
    private void assertLadderIsValid(UUID parentId, GoalLevel childLevel, UUID childId) {
        Goal parent = require(parentId);

        if (childId != null && parentId.equals(childId)) {
            throw new BusinessRuleException("A goal cannot ladder up to itself");
        }
        if (!childLevel.canLadderUpTo(parent.getLevel())) {
            throw new BusinessRuleException(
                    "A %s goal cannot ladder up to a %s goal — objectives roll up to broader ones"
                            .formatted(childLevel, parent.getLevel()));
        }

        if (childId == null) {
            return;
        }
        Set<UUID> visited = new HashSet<>();
        UUID cursor = parent.getParentGoalId();
        int hops = 0;
        while (cursor != null && hops++ < 32) {
            if (cursor.equals(childId)) {
                throw new BusinessRuleException(
                        "That parent would create a cycle: the goal already sits above it");
            }
            if (!visited.add(cursor)) {
                break;
            }
            cursor = goalRepository.findByIdAndDeletedAtIsNull(cursor)
                    .map(Goal::getParentGoalId)
                    .orElse(null);
        }
    }

    private KeyResult persistKeyResult(UUID goalId, CreateKeyResultRequest request, short position,
                                       UUID organizationId) {
        BigDecimal start = request.startValue() == null ? BigDecimal.ZERO : request.startValue();
        if (request.targetValue().compareTo(start) == 0) {
            throw new BusinessRuleException(
                    "A key result's target must differ from its starting value, or there is nothing to measure");
        }
        if (request.ownerUserId() != null) {
            userService.requireActiveMember(request.ownerUserId());
        }

        KeyResult keyResult = KeyResult.builder()
                .goalId(goalId)
                .title(request.title().trim())
                .metricType(request.metricType() == null ? MetricType.NUMBER : request.metricType())
                .unit(request.unit())
                .startValue(start)
                .currentValue(start)
                .targetValue(request.targetValue())
                // Inferred when the caller does not say: a target below the start can
                // only mean the number is meant to come down.
                .direction(request.direction() != null
                        ? request.direction()
                        : request.targetValue().compareTo(start) < 0
                                ? MetricDirection.DECREASE
                                : MetricDirection.INCREASE)
                .ownerUserId(request.ownerUserId())
                .position(position)
                .build();
        keyResult.setOrganizationId(organizationId);
        // Paced from the outset: a result created on day one of a quarter is on
        // track at 0%, and only a goal with no dates falls back to a flat default.
        goalRepository.findByIdAndDeletedAtIsNull(goalId)
                .ifPresent(parent -> keyResult.refreshStatus(parent.expectedProgressPct(LocalDate.now())));
        return keyResultRepository.save(keyResult);
    }

    private long count(List<Goal> goals, GoalStatus status) {
        return goals.stream().filter(goal -> goal.getStatus() == status).count();
    }

    private Map<UUID, UserSummary> ownersFor(List<Goal> goals, Map<UUID, List<KeyResult>> results) {
        Set<UUID> ids = new HashSet<>();
        goals.forEach(goal -> {
            if (goal.getOwnerUserId() != null) ids.add(goal.getOwnerUserId());
        });
        results.values().forEach(list -> list.forEach(keyResult -> {
            if (keyResult.getOwnerUserId() != null) ids.add(keyResult.getOwnerUserId());
        }));
        return ids.isEmpty() ? Map.of() : userService.summariesFor(ids);
    }

    // ------------------------------------------------------------------ mapping

    private GoalResponse toResponse(Goal goal, List<KeyResult> results, Map<UUID, UserSummary> people) {
        UserSummary owner = goal.getOwnerUserId() == null ? null : people.get(goal.getOwnerUserId());
        LocalDate today = LocalDate.now();

        return new GoalResponse(goal.getId(), goal.getWorkspaceId(), goal.getProjectId(),
                goal.getParentGoalId(), goal.getTitle(), goal.getDescription(), goal.getPeriod(),
                goal.getStartDate(), goal.getTargetDate(), goal.getStatus(), goal.getLevel(),
                goal.getOwnerUserId(),
                owner == null ? null : owner.fullName(),
                owner == null ? null : owner.avatarUrl(),
                goal.getProgressPct(),
                goal.isOverdue(today),
                goal.getTargetDate() == null
                        ? null
                        : (int) ChronoUnit.DAYS.between(today, goal.getTargetDate()),
                results.stream().map(result -> toResponse(result, people)).toList(),
                goal.getCreatedAt(), goal.getUpdatedAt());
    }

    private KeyResultResponse toResponse(KeyResult keyResult, Map<UUID, UserSummary> people) {
        UserSummary owner =
                keyResult.getOwnerUserId() == null ? null : people.get(keyResult.getOwnerUserId());

        List<LinkedItemResponse> links = linkRepository.findByKeyResultId(keyResult.getId()).stream()
                // The item's key and title are resolved by the caller when it needs
                // them; this module does not reach into backlog's repositories.
                .map(link -> new LinkedItemResponse(link.getId(), link.getEntityType(),
                        link.getEntityId(), null, null))
                .toList();

        return new KeyResultResponse(keyResult.getId(), keyResult.getGoalId(), keyResult.getTitle(),
                keyResult.getMetricType(), keyResult.getUnit(), keyResult.getStartValue(),
                keyResult.getCurrentValue(), keyResult.getTargetValue(), keyResult.getDirection(),
                keyResult.getStatus(), keyResult.progressPct(), keyResult.isAchieved(),
                keyResult.getOwnerUserId(), owner == null ? null : owner.fullName(),
                keyResult.getPosition(), links, keyResult.getUpdatedAt());
    }

    private CheckinResponse toResponse(KeyResultCheckin checkin, Map<UUID, UserSummary> people) {
        UserSummary actor = checkin.getRecordedBy() == null ? null : people.get(checkin.getRecordedBy());
        BigDecimal delta = checkin.getPreviousValue() == null
                ? null
                : checkin.getValue().subtract(checkin.getPreviousValue());

        return new CheckinResponse(checkin.getId(), checkin.getKeyResultId(), checkin.getValue(),
                checkin.getPreviousValue(), delta, checkin.getConfidence(), checkin.getNote(),
                checkin.getRecordedBy(), actor == null ? null : actor.fullName(),
                checkin.getRecordedAt());
    }
}
