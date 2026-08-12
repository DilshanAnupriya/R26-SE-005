package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.util.LexoRank;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.application.ChangeSet;
import com.sprintiq.pms.modules.backlog.domain.*;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import com.sprintiq.pms.modules.projects.application.ItemKeyService;
import com.sprintiq.pms.modules.projects.application.LabelService;
import com.sprintiq.pms.modules.projects.application.ProjectLookupService;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Epics: the layer between a milestone and the stories that deliver it. */
@Service
@RequiredArgsConstructor
public class EpicService {

    public static final Set<String> SORT_FIELDS =
            Set.of("rank", "itemKey", "title", "status", "priority", "targetDate", "createdAt", "updatedAt");

    private final EpicRepository epicRepository;
    private final StoryRepository storyRepository;
    private final ProjectLookupService projectLookup;
    private final ItemKeyService itemKeyService;
    private final LabelService labelService;
    private final ActivityService activityService;

    @Transactional(readOnly = true)
    public PageResponse<EpicResponse> search(UUID projectId, EpicStatus status, Priority priority,
                                             UUID milestoneId, PageQuery query) {
        projectLookup.require(projectId);
        return PageResponse.from(epicRepository.search(projectId, status, priority, milestoneId,
                query.searchTerm(), query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.ASC, "rank"))),
                this::toResponse);
    }

    @Transactional(readOnly = true)
    public EpicResponse get(UUID epicId) {
        return toResponse(require(epicId));
    }

    @Transactional
    public EpicResponse create(CreateEpicRequest request) {
        projectLookup.requireWritable(request.projectId());

        Epic epic = Epic.builder()
                .projectId(request.projectId())
                .milestoneId(request.milestoneId())
                .itemKey(itemKeyService.allocate(request.projectId()))
                .title(request.title().trim())
                .description(request.description())
                .status(request.status() == null ? EpicStatus.OPEN : request.status())
                .priority(request.priority() == null ? Priority.MEDIUM : request.priority())
                .color(request.color())
                .startDate(request.startDate())
                .targetDate(request.targetDate())
                .rank(nextRank(request.projectId()))
                .build();
        epic.setOrganizationId(TenantContext.requireOrganizationId());
        epic = epicRepository.save(epic);

        activityService.recordCreated(target(epic), "Epic %s created".formatted(epic.getItemKey()));
        return toResponse(epic);
    }

    @Transactional
    public EpicResponse update(UUID epicId, UpdateEpicRequest request) {
        Epic epic = require(epicId);
        ChangeSet changes = new ChangeSet();

        changes.apply("title", epic.getTitle(), trim(request.title()), epic::setTitle);
        changes.apply("description", epic.getDescription(), request.description(), epic::setDescription);
        changes.apply("status", epic.getStatus(), request.status(), epic::setStatus);
        changes.apply("priority", epic.getPriority(), request.priority(), epic::setPriority);
        changes.apply("milestoneId", epic.getMilestoneId(), request.milestoneId(), epic::setMilestoneId);
        changes.apply("color", epic.getColor(), request.color(), epic::setColor);
        changes.apply("startDate", epic.getStartDate(), request.startDate(), epic::setStartDate);
        changes.apply("targetDate", epic.getTargetDate(), request.targetDate(), epic::setTargetDate);

        if (changes.isEmpty()) {
            return toResponse(epic);
        }
        epic = epicRepository.save(epic);
        activityService.recordChanges(target(epic), changes);
        return toResponse(epic);
    }

    /**
     * Soft-deletes the epic. Its stories survive and simply lose their epic — deleting
     * an epic is a re-organisation of the plan, not a decision to throw the work away,
     * and cascading would silently destroy estimated, ranked backlog items.
     */
    @Transactional
    public void delete(UUID epicId) {
        Epic epic = require(epicId);
        List<Story> orphans = storyRepository.findByEpicIdAndDeletedAtIsNullOrderByRankAsc(epicId);
        orphans.forEach(story -> story.setEpicId(null));
        storyRepository.saveAll(orphans);

        epic.setDeletedAt(Instant.now());
        epicRepository.save(epic);
        activityService.recordDeleted(target(epic),
                "Epic %s deleted; %d story/stories returned to the backlog"
                        .formatted(epic.getItemKey(), orphans.size()));
    }

    @Transactional(readOnly = true)
    public long countInProject(UUID projectId) {
        return epicRepository.countByProjectIdAndDeletedAtIsNull(projectId);
    }

    // ------------------------------------------------------------------ internals

    private Epic require(UUID epicId) {
        return epicRepository.findByIdAndDeletedAtIsNull(epicId)
                .orElseThrow(() -> new ResourceNotFoundException("Epic", epicId));
    }

    /** Validates that an epic exists in the given project — used when re-parenting a story. */
    @Transactional(readOnly = true)
    public void requireInProject(UUID epicId, UUID projectId) {
        Epic epic = require(epicId);
        if (!epic.getProjectId().equals(projectId)) {
            throw new ConflictException("Epic %s belongs to a different project".formatted(epic.getItemKey()));
        }
    }

    private String nextRank(UUID projectId) {
        return epicRepository.findMaxRank(projectId).map(LexoRank::after).orElse(LexoRank.INITIAL);
    }

    private ActivityService.Target target(Epic epic) {
        return ActivityService.Target.of(EntityType.EPIC, epic.getId(), epic.getItemKey(), epic.getProjectId());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private EpicResponse toResponse(Epic epic) {
        List<Story> stories = storyRepository.findByEpicIdAndDeletedAtIsNullOrderByRankAsc(epic.getId());
        long completed = stories.stream().filter(s -> s.getStatus().isCompleted()).count();
        BigDecimal points = stories.stream()
                .map(Story::getStoryPoints)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new EpicResponse(epic.getId(), epic.getProjectId(), epic.getMilestoneId(), epic.getItemKey(),
                epic.getTitle(), epic.getDescription(), epic.getStatus(), epic.getPriority(), epic.getColor(),
                epic.getStartDate(), epic.getTargetDate(), epic.getRank(), stories.size(), completed, points,
                labelService.labelsFor(EntityType.EPIC, epic.getId()),
                epic.getCreatedAt(), epic.getUpdatedAt());
    }
}
