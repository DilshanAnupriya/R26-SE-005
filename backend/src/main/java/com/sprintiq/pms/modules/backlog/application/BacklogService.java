package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.util.LexoRank;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import com.sprintiq.pms.modules.projects.application.LabelService;
import com.sprintiq.pms.modules.users.application.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Backlog-level operations: reordering and bulk editing.
 *
 * <p>Grooming a backlog is a batch activity — drag six stories above another, move a
 * whole epic's worth into the next sprint. Doing that through the per-story endpoints
 * means six round trips, six transactions and a visibly jumpy list; these operations
 * apply the whole change atomically.</p>
 */
@Service
@RequiredArgsConstructor
public class BacklogService {

    /** Guard on bulk operations, matching the maximum page size a client can be showing. */
    private static final int MAX_BULK_ITEMS = 100;

    private final StoryRepository storyRepository;
    private final StoryService storyService;
    private final EpicService epicService;
    private final LabelService labelService;
    private final UserService userService;
    private final ActivityService activityService;

    /**
     * Moves items between two neighbours.
     *
     * <p>Only the moved rows are written — that is the whole point of the
     * {@link LexoRank} scheme. Reordering by integer position would rewrite every row
     * below the insertion point, turning a drag of one card into an update of the
     * entire backlog.</p>
     */
    @Transactional
    public List<RankedItemResponse> rank(UUID projectId, RankRequest request) {
        List<Story> stories = loadForProject(projectId, request.itemIds());

        String afterRank = boundaryRank(projectId, request.afterId());
        String beforeRank = boundaryRank(projectId, request.beforeId());

        if (afterRank != null && beforeRank != null && afterRank.compareTo(beforeRank) >= 0) {
            throw new BusinessRuleException(
                    "afterId must sit above beforeId in the current backlog order");
        }

        List<String> ranks;
        try {
            ranks = LexoRank.sequence(afterRank, beforeRank, stories.size());
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException("Cannot place items between the supplied neighbours: "
                    + ex.getMessage());
        }

        // Apply in the order the client listed them, not the order they came back from
        // the database — the request's sequence is the user's intended arrangement.
        Map<UUID, Story> byId = stories.stream()
                .collect(java.util.stream.Collectors.toMap(Story::getId, story -> story));
        List<RankedItemResponse> result = new ArrayList<>(stories.size());
        int index = 0;
        for (UUID itemId : request.itemIds()) {
            Story story = byId.get(itemId);
            story.setRank(ranks.get(index++));
            result.add(new RankedItemResponse(story.getId(), story.getRank()));
        }
        storyRepository.saveAll(byId.values());

        if (stories.size() == 1) {
            Story moved = stories.get(0);
            activityService.record(
                    ActivityService.Target.of(EntityType.STORY, moved.getId(), moved.getItemKey(), projectId),
                    ActivityAction.RANKED, "%s reordered in the backlog".formatted(moved.getItemKey()));
        }
        return result;
    }

    /**
     * Applies the same field changes to many stories.
     *
     * <p>Partial success is deliberate: a grooming selection of forty stories where two
     * are in a state that forbids the change should apply to the other thirty-eight and
     * report what it skipped, rather than failing the whole batch and leaving the user
     * to work out which two.</p>
     */
    @Transactional
    public BulkUpdateResponse bulkUpdate(UUID projectId, BulkUpdateRequest request) {
        List<Story> stories = loadForProject(projectId, request.itemIds());

        if (request.epicId() != null) {
            epicService.requireInProject(request.epicId(), projectId);
        }
        if (request.assigneeId() != null) {
            userService.requireActiveMember(request.assigneeId());
        }

        List<UUID> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Story story : stories) {
            boolean changed = false;

            if (request.status() != null && request.status() != story.getStatus()) {
                story.transitionTo(request.status());
                changed = true;
            }
            if (request.priority() != null && request.priority() != story.getPriority()) {
                story.setPriority(request.priority());
                changed = true;
            }
            if (request.epicId() != null && !request.epicId().equals(story.getEpicId())) {
                story.setEpicId(request.epicId());
                changed = true;
            }
            if (request.assigneeId() != null && !request.assigneeId().equals(story.getAssigneeId())) {
                story.setAssigneeId(request.assigneeId());
                changed = true;
            }
            if (Boolean.TRUE.equals(request.removeFromSprint())) {
                if (story.getStatus().isStarted()) {
                    skipped.add("%s is already in progress and was left in its sprint"
                            .formatted(story.getItemKey()));
                    continue;
                }
                if (story.getSprintId() != null) {
                    story.setSprintId(null);
                    changed = true;
                }
            } else if (request.sprintId() != null && !request.sprintId().equals(story.getSprintId())) {
                story.setSprintId(request.sprintId());
                changed = true;
            }
            if (request.addLabelIds() != null && !request.addLabelIds().isEmpty()) {
                Set<UUID> merged = new LinkedHashSet<>(
                        labelService.labelsFor(EntityType.STORY, story.getId()).stream()
                                .map(label -> label.id()).toList());
                merged.addAll(request.addLabelIds());
                labelService.apply(EntityType.STORY, story.getId(), List.copyOf(merged));
                changed = true;
            }

            if (changed) {
                updated.add(story.getId());
                activityService.record(
                        ActivityService.Target.of(EntityType.STORY, story.getId(), story.getItemKey(), projectId),
                        ActivityAction.UPDATED, "%s updated in a bulk edit".formatted(story.getItemKey()));
            }
        }

        storyRepository.saveAll(stories);
        return new BulkUpdateResponse(updated.size(), updated, skipped);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Loads the requested stories and asserts they all belong to the project in the
     * path. Without this check a caller could pass ids from another of their projects
     * and reorder a backlog they were not looking at.
     */
    private List<Story> loadForProject(UUID projectId, List<UUID> itemIds) {
        if (itemIds.size() > MAX_BULK_ITEMS) {
            throw new BusinessRuleException(
                    "At most %d items can be changed in one call".formatted(MAX_BULK_ITEMS));
        }
        List<Story> stories = storyRepository.findByIdInAndDeletedAtIsNull(itemIds);
        if (stories.size() != new HashSet<>(itemIds).size()) {
            Set<UUID> found = stories.stream().map(Story::getId)
                    .collect(java.util.stream.Collectors.toSet());
            UUID missing = itemIds.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw new ResourceNotFoundException("Story", missing);
        }
        stories.stream()
                .filter(story -> !story.getProjectId().equals(projectId))
                .findFirst()
                .ifPresent(story -> {
                    throw new ConflictException("%s belongs to a different project"
                            .formatted(story.getItemKey()));
                });
        return stories;
    }

    /** Current rank of a neighbour, or null for "the end of the list". */
    private String boundaryRank(UUID projectId, UUID neighbourId) {
        if (neighbourId == null) {
            return null;
        }
        Story neighbour = storyRepository.findByIdAndDeletedAtIsNull(neighbourId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", neighbourId));
        if (!neighbour.getProjectId().equals(projectId)) {
            throw new ConflictException("Neighbour %s belongs to a different project"
                    .formatted(neighbour.getItemKey()));
        }
        return neighbour.getRank();
    }
}
