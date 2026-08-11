package com.sprintiq.pms.modules.roadmap.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.application.ProjectSummary;
import com.sprintiq.pms.modules.roadmap.application.port.InitiativeWorkSource;
import com.sprintiq.pms.modules.roadmap.application.port.LinkedWork;
import com.sprintiq.pms.modules.roadmap.domain.*;
import com.sprintiq.pms.modules.roadmap.web.dto.RoadmapDtos.*;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The roadmap: initiatives, the work behind them, and the lanes they are drawn in.
 *
 * <p>One rule above all others: an initiative's progress is never typed in. It is
 * always read off the epics and stories linked to it. A roadmap whose percentages are
 * hand-maintained reports whatever the last person to remember it believed, which is
 * exactly the failure that makes people stop trusting roadmaps.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoadmapService {

    private final InitiativeRepository initiativeRepository;
    private final InitiativeItemRepository itemRepository;
    private final ProjectService projectService;
    private final UserService userService;
    private final AuditService auditService;

    /**
     * One per linkable entity type, contributed by the module that owns it. Empty on a
     * deployment without the delivery modules, in which case every initiative simply
     * reports zero progress rather than failing.
     */
    private final List<InitiativeWorkSource> workSources;

    // ------------------------------------------------------------------ queries

    /**
     * The whole roadmap, assembled the way it is drawn.
     *
     * <p>The quarter columns come from what is actually on the roadmap rather than from
     * a fixed window: if everything lands in Q3 there is one column, and if something
     * slips to Q1 next year the column appears. A client deriving that itself would
     * eventually disagree with another client doing the same.</p>
     */
    @Transactional(readOnly = true)
    public RoadmapResponse roadmap(UUID projectId, InitiativeStatus status,
                                   LocalDate from, LocalDate to) {
        // The window is applied here rather than in the query — see the note on
        // findForRoadmap. An undated initiative is kept whatever the window: it has not
        // been scheduled out of the range, it has not been scheduled at all.
        List<Initiative> initiatives = initiativeRepository.findForRoadmap(projectId, status).stream()
                .filter(initiative -> initiative.getTargetDate() == null
                        || ((from == null || !initiative.getTargetDate().isBefore(from))
                            && (to == null || !initiative.getTargetDate().isAfter(to))))
                .toList();
        if (initiatives.isEmpty()) {
            return new RoadmapResponse(List.of(), List.of(), 0, 0, 0);
        }

        Map<UUID, List<ItemResponse>> itemsByInitiative = resolveItems(initiatives);
        Map<UUID, ProjectSummary> projects = projectService.summariesFor(
                initiatives.stream().map(Initiative::getProjectId).filter(Objects::nonNull).toList());
        Map<UUID, UserSummary> owners = userService.summariesFor(
                initiatives.stream().map(Initiative::getOwnerUserId).filter(Objects::nonNull).toList());

        LocalDate today = LocalDate.now();
        List<InitiativeResponse> responses = initiatives.stream()
                .map(initiative -> toResponse(initiative,
                        itemsByInitiative.getOrDefault(initiative.getId(), List.of()),
                        projects, owners, today))
                .toList();

        // Sorted rather than insertion-ordered: quarters are strings like "2026-Q3",
        // which sort correctly as text precisely because of the zero-padded shape.
        List<String> quarters = responses.stream()
                .map(InitiativeResponse::quarter)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        // LinkedHashMap so lanes keep the order the initiatives arrived in, which is
        // the position the roadmap was arranged into by hand.
        Map<UUID, List<InitiativeResponse>> byProject = responses.stream()
                .collect(Collectors.groupingBy(
                        response -> response.projectId() == null ? CROSS_PROJECT : response.projectId(),
                        LinkedHashMap::new, Collectors.toList()));

        List<LaneResponse> lanes = byProject.entrySet().stream()
                .map(entry -> {
                    boolean cross = CROSS_PROJECT.equals(entry.getKey());
                    ProjectSummary project = cross ? null : projects.get(entry.getKey());
                    return new LaneResponse(
                            cross ? null : entry.getKey(),
                            cross ? "ORG" : project == null ? "—" : project.projectKey(),
                            cross ? "Cross-project" : project == null ? "Unknown project" : project.name(),
                            entry.getValue());
                })
                .toList();

        return new RoadmapResponse(
                quarters,
                lanes,
                responses.size(),
                (int) responses.stream().filter(r -> r.status() == InitiativeStatus.AT_RISK).count(),
                (int) responses.stream().filter(r -> r.status() == InitiativeStatus.SHIPPED).count());
    }

    /** Sentinel key for the cross-project lane; never persisted. */
    private static final UUID CROSS_PROJECT = new UUID(0L, 0L);

    @Transactional(readOnly = true)
    public InitiativeResponse get(UUID initiativeId) {
        Initiative initiative = require(initiativeId);
        return toResponse(initiative,
                resolveItems(List.of(initiative)).getOrDefault(initiativeId, List.of()),
                projectService.summariesFor(singleton(initiative.getProjectId())),
                userService.summariesFor(singleton(initiative.getOwnerUserId())),
                LocalDate.now());
    }

    private static List<UUID> singleton(UUID id) {
        return id == null ? List.of() : List.of(id);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public InitiativeResponse create(CreateInitiativeRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();
        assertDatesOrdered(request.startDate(), request.targetDate());
        if (request.ownerUserId() != null) {
            userService.requireActiveMember(request.ownerUserId());
        }

        short position = (short) (initiativeRepository.findMaxPosition(request.projectId())
                .orElse((short) -1) + 1);

        Initiative initiative = Initiative.builder()
                .projectId(request.projectId())
                .title(request.title().trim())
                .description(request.description())
                .startDate(request.startDate())
                .targetDate(request.targetDate())
                .status(request.status() == null ? InitiativeStatus.PLANNED : request.status())
                .confidence(request.confidence() == null ? Confidence.MEDIUM : request.confidence())
                .ownerUserId(request.ownerUserId())
                .position(position)
                .build();
        initiative.setOrganizationId(organizationId);
        initiative = initiativeRepository.save(initiative);

        if (request.items() != null) {
            for (LinkRequest item : request.items()) {
                persistLink(initiative.getId(), item, organizationId);
            }
        }

        recalculate(initiative);
        auditService.record("INITIATIVE_CREATED", "Initiative", initiative.getId(), null,
                Map.of("title", initiative.getTitle(),
                        "items", request.items() == null ? 0 : request.items().size()));

        return get(initiative.getId());
    }

    @Transactional
    public InitiativeResponse update(UUID initiativeId, UpdateInitiativeRequest request) {
        Initiative initiative = require(initiativeId);

        if (request.title() != null) initiative.setTitle(request.title().trim());
        if (request.description() != null) initiative.setDescription(request.description());
        if (request.projectId() != null) initiative.setProjectId(request.projectId());
        if (request.startDate() != null) initiative.setStartDate(request.startDate());
        if (request.targetDate() != null) initiative.setTargetDate(request.targetDate());
        if (request.confidence() != null) initiative.setConfidence(request.confidence());
        if (request.position() != null) initiative.setPosition(request.position());
        if (request.ownerUserId() != null) {
            userService.requireActiveMember(request.ownerUserId());
            initiative.setOwnerUserId(request.ownerUserId());
        }
        // Status is settable — shipping is a human judgement, and so is cancelling —
        // but only deliberately. Everything else derives it.
        if (request.status() != null) initiative.setStatus(request.status());

        assertDatesOrdered(initiative.getStartDate(), initiative.getTargetDate());
        recalculate(initiative);

        return get(initiativeId);
    }

    @Transactional
    public void delete(UUID initiativeId) {
        Initiative initiative = require(initiativeId);
        initiative.setDeletedAt(java.time.Instant.now());
        initiativeRepository.save(initiative);
        auditService.record("INITIATIVE_DELETED", "Initiative", initiativeId, null,
                Map.of("title", initiative.getTitle()));
    }

    @Transactional
    public InitiativeResponse linkItem(UUID initiativeId, LinkRequest request) {
        Initiative initiative = require(initiativeId);
        if (itemRepository.existsByInitiativeIdAndEntityTypeAndEntityId(
                initiativeId, request.entityType(), request.entityId())) {
            throw new ConflictException("That item is already part of this initiative");
        }
        persistLink(initiativeId, request, initiative.getOrganizationId());
        recalculate(initiative);
        return get(initiativeId);
    }

    @Transactional
    public InitiativeResponse unlinkItem(UUID initiativeId, EntityType entityType, UUID entityId) {
        Initiative initiative = require(initiativeId);
        itemRepository.deleteByInitiativeIdAndEntityTypeAndEntityId(initiativeId, entityType, entityId);
        recalculate(initiative);
        return get(initiativeId);
    }

    // ----------------------------------------------------------------- scheduled

    /**
     * Recomputes every open initiative from its linked work, shortly after midnight.
     *
     * <p>Two things go stale between writes, and this fixes both. Progress drifts
     * because the backlog does not know the roadmap exists — a story moving to DONE
     * cannot notify a card that happens to link its epic, and making it try would
     * couple the two modules for the sake of a number that can wait until tomorrow.
     * Status drifts because it is measured against the calendar, which advances with
     * nobody touching anything.</p>
     *
     * <p>Runs with no tenant context, so it sees every organization. The linked ids are
     * their own scope: each initiative resolves the epics its own tenant recorded.</p>
     */
    @Scheduled(cron = "0 25 2 * * *")
    @Transactional
    public void recomputeOpenInitiatives() {
        List<Initiative> open = initiativeRepository.findByStatusInAndDeletedAtIsNull(
                EnumSet.of(InitiativeStatus.PLANNED, InitiativeStatus.IN_PROGRESS,
                        InitiativeStatus.AT_RISK));
        if (open.isEmpty()) {
            return;
        }

        // Batched across the whole sweep: one call per entity type, not per initiative.
        Map<UUID, List<ItemResponse>> itemsByInitiative = resolveItems(open);

        LocalDate today = LocalDate.now();
        List<Initiative> changed = new ArrayList<>();
        for (Initiative initiative : open) {
            List<ItemResponse> items = itemsByInitiative.getOrDefault(initiative.getId(), List.of());
            BigDecimal previousProgress = initiative.getProgressPct();
            InitiativeStatus previousStatus = initiative.getStatus();

            initiative.recalculate(averageProgress(items), today,
                    items.stream().anyMatch(ItemResponse::exists));

            if (initiative.getStatus() != previousStatus
                    || initiative.getProgressPct().compareTo(previousProgress) != 0) {
                changed.add(initiative);
            }
        }

        if (!changed.isEmpty()) {
            initiativeRepository.saveAll(changed);
            log.info("Recomputed {} of {} open initiatives from their linked work",
                    changed.size(), open.size());
        }
    }

    // ------------------------------------------------------------------ internals

    private Initiative require(UUID initiativeId) {
        return initiativeRepository.findByIdAndDeletedAtIsNull(initiativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Initiative", initiativeId));
    }

    private static void assertDatesOrdered(LocalDate start, LocalDate target) {
        if (start != null && target != null && target.isBefore(start)) {
            throw new BusinessRuleException(
                    "An initiative's target date cannot fall before its start date");
        }
    }

    private void persistLink(UUID initiativeId, LinkRequest request, UUID organizationId) {
        InitiativeItem item = InitiativeItem.builder()
                .initiativeId(initiativeId)
                .entityType(request.entityType())
                .entityId(request.entityId())
                .build();
        item.setOrganizationId(organizationId);
        itemRepository.save(item);
    }

    /**
     * Recomputes progress from the linked work and re-derives status, then saves.
     *
     * <p>Called on every path that can change the link set. It is deliberately not
     * called when a linked story moves — the backlog does not know the roadmap exists,
     * and making it publish an event for every status change would couple the two.
     * The nightly sweep closes that gap, so a card is at most a day stale.</p>
     */
    private void recalculate(Initiative initiative) {
        List<ItemResponse> items = resolveItems(List.of(initiative))
                .getOrDefault(initiative.getId(), List.of());
        boolean hasLinkedWork = items.stream().anyMatch(ItemResponse::exists);

        initiative.recalculate(averageProgress(items), LocalDate.now(), hasLinkedWork);
        initiativeRepository.save(initiative);
    }

    /**
     * The unweighted mean of the linked items' progress.
     *
     * <p>Unweighted on purpose. Weighting by size makes the roadmap card move fastest
     * when the biggest epic moves, which is exactly when a stakeholder should be
     * looking at the epic rather than the summary. Items whose target no longer exists
     * are excluded rather than counted as zero — a deleted epic should not drag the
     * initiative down, it should stop being part of the sum.</p>
     */
    private static BigDecimal averageProgress(List<ItemResponse> items) {
        List<ItemResponse> live = items.stream().filter(ItemResponse::exists).toList();
        if (live.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return live.stream()
                .map(ItemResponse::progressPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(live.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Resolves every link on the page in one call per entity type.
     *
     * <p>Grouped by type first so each work source is asked once for the whole batch —
     * the per-item shape of this question is how a roadmap becomes two hundred round
     * trips.</p>
     */
    private Map<UUID, List<ItemResponse>> resolveItems(List<Initiative> initiatives) {
        List<InitiativeItem> links = itemRepository.findByInitiativeIdIn(
                initiatives.stream().map(Initiative::getId).toList());
        if (links.isEmpty()) {
            return Map.of();
        }

        Map<EntityType, Map<UUID, LinkedWork>> described = new EnumMap<>(EntityType.class);
        Map<EntityType, List<InitiativeItem>> byType = links.stream()
                .collect(Collectors.groupingBy(InitiativeItem::getEntityType));

        for (Map.Entry<EntityType, List<InitiativeItem>> entry : byType.entrySet()) {
            InitiativeWorkSource source = workSources.stream()
                    .filter(candidate -> candidate.supports() == entry.getKey())
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                continue;
            }
            described.put(entry.getKey(), source.describe(
                    entry.getValue().stream().map(InitiativeItem::getEntityId).distinct().toList()));
        }

        Map<UUID, List<ItemResponse>> byInitiative = new HashMap<>();
        for (InitiativeItem link : links) {
            LinkedWork work = described
                    .getOrDefault(link.getEntityType(), Map.of())
                    .getOrDefault(link.getEntityId(), LinkedWork.missing());

            byInitiative.computeIfAbsent(link.getInitiativeId(), key -> new ArrayList<>())
                    .add(new ItemResponse(link.getId(), link.getEntityType(), link.getEntityId(),
                            work.itemKey(), work.title(), work.exists(),
                            work.totalItems(), work.completedItems(), work.progressPct()));
        }
        return byInitiative;
    }

    private InitiativeResponse toResponse(Initiative initiative, List<ItemResponse> items,
                                          Map<UUID, ProjectSummary> projects,
                                          Map<UUID, UserSummary> owners, LocalDate today) {
        ProjectSummary project =
                initiative.getProjectId() == null ? null : projects.get(initiative.getProjectId());
        UserSummary owner =
                initiative.getOwnerUserId() == null ? null : owners.get(initiative.getOwnerUserId());

        return new InitiativeResponse(
                initiative.getId(),
                initiative.getProjectId(),
                project == null ? null : project.projectKey(),
                project == null ? null : project.name(),
                initiative.getTitle(),
                initiative.getDescription(),
                initiative.getStartDate(),
                initiative.getTargetDate(),
                initiative.quarter(),
                initiative.getStatus(),
                initiative.getConfidence(),
                initiative.getOwnerUserId(),
                owner == null ? null : owner.fullName(),
                initiative.getProgressPct(),
                initiative.expectedProgressPct(today),
                initiative.isOverdue(today),
                initiative.getTargetDate() == null
                        ? null
                        : (int) ChronoUnit.DAYS.between(today, initiative.getTargetDate()),
                initiative.getPosition(),
                items,
                initiative.getCreatedAt(),
                initiative.getUpdatedAt());
    }
}
