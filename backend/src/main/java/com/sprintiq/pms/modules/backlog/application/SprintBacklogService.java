package com.sprintiq.pms.modules.backlog.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.activity.application.ActivityService;
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
import com.sprintiq.pms.modules.backlog.domain.Story;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The backlog module's published surface for sprint planning.
 *
 * <p>Sprints need to pull stories in, push them back out and total their points, but
 * {@code sprint_id} is a column on {@code stories} — owned here. Rather than let the
 * sprints module reach into this module's repository, it calls these operations, which
 * keeps the rules about what may move (and what that does to a story's history) in one
 * place.</p>
 */
@Service
@RequiredArgsConstructor
public class SprintBacklogService {

    private final StoryRepository storyRepository;
    private final StoryService storyService;
    private final ActivityService activityService;
    /** Carries a story's tasks with it — see {@code moveStoryTasksToSprint}. */
    private final StoryTaskCounter storyTasks;

    /** Points summary a sprint needs, without exposing the stories themselves. */
    public record SprintPoints(BigDecimal committed, BigDecimal completed, long storyCount,
                               long completedCount) {}

    /**
     * Everything the sprint metrics and burndown snapshots need, in one pass over the
     * sprint's stories. Separate from {@link SprintPoints} so the common case — the
     * sprint header showing points — does not pay for the extra counting.
     */
    public record SprintSnapshot(BigDecimal committed, BigDecimal completed, BigDecimal remaining,
                                 long storyCount, long completedCount, long blockedCount,
                                 long defectCount, long reopenedCount, long unestimatedCount,
                                 long contributorCount) {}

    /**
     * Stories in this sprint whose work started before the sprint did.
     *
     * <p>Separate from {@link #snapshotOf} because it needs the sprint's start date,
     * and the snapshot deliberately knows nothing about the sprint row — it counts what
     * is in the sprint, not what the sprint is.</p>
     */
    @Transactional(readOnly = true)
    public long carriedOverIn(UUID sprintId, java.time.Instant sprintStart) {
        return sprintStart == null ? 0L : storyRepository.countCarriedOver(sprintId, sprintStart);
    }

    @Transactional(readOnly = true)
    public SprintSnapshot snapshotOf(UUID sprintId) {
        List<Story> stories = storyRepository.findBySprintIdAndDeletedAtIsNull(sprintId);

        BigDecimal committed = BigDecimal.ZERO;
        BigDecimal completed = BigDecimal.ZERO;
        long completedCount = 0;
        long blocked = 0;
        long defects = 0;
        long reopened = 0;
        long unestimated = 0;
        java.util.Set<UUID> contributors = new java.util.HashSet<>();

        for (Story story : stories) {
            if (story.getStoryPoints() != null) {
                committed = committed.add(story.getStoryPoints());
            } else {
                unestimated++;
            }
            completed = completed.add(story.velocityPoints());
            if (story.getStatus().isCompleted()) completedCount++;
            if (story.isBlocked()) blocked++;
            if (story.getType().isDefect()) defects++;
            reopened += story.getReopenedCount();
            if (story.getAssigneeId() != null) contributors.add(story.getAssigneeId());
        }

        BigDecimal remaining = committed.subtract(completed);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            remaining = BigDecimal.ZERO;
        }
        return new SprintSnapshot(committed, completed, remaining, stories.size(), completedCount, blocked,
                defects, reopened, unestimated, contributors.size());
    }

    @Transactional(readOnly = true)
    public List<StoryResponse> storiesIn(UUID sprintId) {
        List<Story> stories = storyRepository.findBySprintIdAndDeletedAtIsNull(sprintId);
        return stories.stream().map(storyService.enrichmentFor(stories)).toList();
    }

    @Transactional(readOnly = true)
    public SprintPoints pointsIn(UUID sprintId) {
        List<Story> stories = storyRepository.findBySprintIdAndDeletedAtIsNull(sprintId);
        BigDecimal committed = stories.stream()
                .map(Story::getStoryPoints)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal completed = stories.stream()
                .map(Story::velocityPoints)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long completedCount = stories.stream().filter(story -> story.getStatus().isCompleted()).count();
        return new SprintPoints(committed, completed, stories.size(), completedCount);
    }

    /**
     * Commits stories to a sprint.
     *
     * <p>A story already in another sprint is moved rather than rejected — dragging a
     * card from one sprint to another is a normal planning action. The refusal is
     * reserved for stories from a different project, which is always a mistake.</p>
     */
    @Transactional
    public List<StoryResponse> addToSprint(UUID sprintId, UUID projectId, List<UUID> storyIds) {
        List<Story> stories = load(storyIds, projectId);
        for (Story story : stories) {
            if (sprintId.equals(story.getSprintId())) {
                continue;
            }
            UUID previous = story.getSprintId();
            story.setSprintId(sprintId);
            // The tasks go too. A story committed to a sprint while its breakdown stays
            // in the backlog is the sprint the cost model prices at zero.
            storyTasks.moveStoryTasksToSprint(story.getId(), sprintId);
            activityService.recordChange(target(story), ActivityAction.MOVED_TO_SPRINT, "sprintId",
                    previous, sprintId, "%s committed to a sprint".formatted(story.getItemKey()));
        }
        storyRepository.saveAll(stories);
        return stories.stream().map(storyService.enrichmentFor(stories)).toList();
    }

    /**
     * Returns a story to the backlog. Work that has already started stays put: pulling
     * an in-progress story out of its sprint erases the commitment it was measured
     * against and quietly flatters the sprint's completion rate.
     */
    @Transactional
    public void removeFromSprint(UUID sprintId, UUID storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", storyId));
        if (!sprintId.equals(story.getSprintId())) {
            throw new ConflictException("%s is not in that sprint".formatted(story.getItemKey()));
        }
        if (story.getStatus().isStarted()) {
            throw new BusinessRuleException(
                    "%s is already in progress; complete it or move it to the next sprint when this one closes"
                            .formatted(story.getItemKey()));
        }
        story.setSprintId(null);
        storyTasks.moveStoryTasksToSprint(storyId, null);
        storyRepository.save(story);
        activityService.recordChange(target(story), ActivityAction.REMOVED_FROM_SPRINT, "sprintId",
                sprintId, null, "%s returned to the backlog".formatted(story.getItemKey()));
    }

    /**
     * Spillover handling when a sprint closes: everything unfinished moves to
     * {@code destinationSprintId}, or back to the backlog when that is null.
     *
     * @return the stories that spilled over
     */
    @Transactional
    public List<Story> spillOver(UUID sprintId, UUID destinationSprintId) {
        List<Story> incomplete = storyRepository.findIncompleteInSprint(sprintId);
        for (Story story : incomplete) {
            story.setSprintId(destinationSprintId);
            activityService.recordChange(target(story), ActivityAction.MOVED_TO_SPRINT, "sprintId",
                    sprintId, destinationSprintId,
                    destinationSprintId == null
                            ? "%s returned to the backlog as sprint spillover".formatted(story.getItemKey())
                            : "%s carried into the next sprint".formatted(story.getItemKey()));
        }
        storyRepository.saveAll(incomplete);
        return incomplete;
    }

    @Transactional(readOnly = true)
    public long countIn(UUID sprintId) {
        return storyRepository.countBySprintIdAndDeletedAtIsNull(sprintId);
    }

    // ------------------------------------------------------------------ internals

    private List<Story> load(List<UUID> storyIds, UUID projectId) {
        List<Story> stories = storyRepository.findByIdInAndDeletedAtIsNull(storyIds);
        if (stories.size() != storyIds.stream().distinct().count()) {
            throw new ResourceNotFoundException("Story", "one or more of the supplied ids");
        }
        stories.stream().filter(story -> !story.getProjectId().equals(projectId)).findFirst()
                .ifPresent(story -> {
                    throw new ConflictException("%s belongs to a different project"
                            .formatted(story.getItemKey()));
                });
        return stories;
    }

    private ActivityService.Target target(Story story) {
        return ActivityService.Target.of(EntityType.STORY, story.getId(), story.getItemKey(),
                story.getProjectId());
    }
}
