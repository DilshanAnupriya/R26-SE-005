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
import com.sprintiq.pms.modules.activity.domain.ActivityAction;
import com.sprintiq.pms.modules.activity.domain.ActivityLog;
import com.sprintiq.pms.modules.backlog.domain.*;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import com.sprintiq.pms.modules.projects.application.*;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.LabelResponse;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * The story aggregate — create, edit, move, estimate, assign, block.
 *
 * <p>Status changes update both the fixed {@link StoryStatus} and the project's custom
 * board column, because the two are different views of the same fact and a user can
 * change either one. Keeping them in step here means no caller has to remember to.</p>
 */
@Service
@RequiredArgsConstructor
public class StoryService {

    public static final Set<String> SORT_FIELDS =
            Set.of("rank", "itemKey", "title", "status", "priority", "storyPoints", "dueDate",
                    "createdAt", "updatedAt", "completedAt");

    private final StoryRepository storyRepository;
    private final EpicService epicService;
    private final ProjectLookupService projectLookup;
    private final ItemKeyService itemKeyService;
    private final WorkflowStateService workflowStateService;
    private final LabelService labelService;
    private final UserService userService;
    private final ActivityService activityService;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<StoryResponse> search(UUID projectId, UUID epicId, StoryStatus status, StoryType type,
                                              Priority priority, UUID assigneeId, UUID sprintId,
                                              boolean backlogOnly, PageQuery query) {
        projectLookup.require(projectId);
        Page<Story> page = storyRepository.search(projectId, epicId, status, type, priority, assigneeId,
                sprintId, backlogOnly, query.searchTerm(),
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.ASC, "rank")));
        return PageResponse.from(page, enrichmentFor(page.getContent()));
    }

    @Transactional(readOnly = true)
    public StoryResponse get(UUID storyId) {
        return toResponse(require(storyId));
    }

    @Transactional(readOnly = true)
    public PageResponse<HistoryEntryResponse> history(UUID storyId, Pageable pageable) {
        require(storyId);
        return PageResponse.from(activityService.history(EntityType.STORY, storyId, pageable),
                StoryService::toHistoryEntry);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public StoryResponse create(CreateStoryRequest request) {
        projectLookup.requireWritable(request.projectId());
        if (request.epicId() != null) {
            epicService.requireInProject(request.epicId(), request.projectId());
        }
        if (request.assigneeId() != null) {
            userService.requireActiveMember(request.assigneeId());
        }

        BigDecimal points = request.storyPoints();
        Story story = Story.builder()
                .projectId(request.projectId())
                .epicId(request.epicId())
                .sprintId(request.sprintId())
                .requirementId(request.requirementId())
                .workflowStateId(workflowStateService.defaultStateId(request.projectId()))
                .itemKey(itemKeyService.allocate(request.projectId()))
                .title(request.title().trim())
                .description(request.description())
                .acceptanceCriteria(request.acceptanceCriteria())
                .type(request.type() == null ? StoryType.STORY : request.type())
                .status(StoryStatus.BACKLOG)
                .priority(request.priority() == null ? Priority.MEDIUM : request.priority())
                .severity(request.severity())
                .storyPoints(points)
                // A story created with a number was estimated by hand; without one it is
                // explicitly unestimated, which is what the grooming filters look for.
                .estimateSource(points == null ? EstimateSource.UNESTIMATED : EstimateSource.MANUAL)
                .businessValue(request.businessValue())
                .rank(nextRank(request.projectId()))
                .assigneeId(request.assigneeId())
                .reporterId(TenantContext.userId().orElse(null))
                .dueDate(request.dueDate())
                .readyChecklist(copy(request.readyChecklist()))
                .doneChecklist(copy(request.doneChecklist()))
                .build();
        story.setOrganizationId(TenantContext.requireOrganizationId());
        story = storyRepository.save(story);

        if (request.labelIds() != null && !request.labelIds().isEmpty()) {
            labelService.apply(EntityType.STORY, story.getId(), request.labelIds());
        }
        activityService.recordCreated(target(story), "%s created".formatted(story.getItemKey()));
        return toResponse(story);
    }

    @Transactional
    public StoryResponse update(UUID storyId, UpdateStoryRequest request) {
        Story story = require(storyId);
        ChangeSet changes = new ChangeSet();

        changes.apply("title", story.getTitle(), trim(request.title()), story::setTitle);
        changes.apply("description", story.getDescription(), request.description(), story::setDescription);
        changes.apply("acceptanceCriteria", story.getAcceptanceCriteria(), request.acceptanceCriteria(),
                story::setAcceptanceCriteria);
        changes.apply("type", story.getType(), request.type(), story::setType);
        changes.apply("priority", story.getPriority(), request.priority(), story::setPriority);
        changes.apply("severity", story.getSeverity(), request.severity(), story::setSeverity);
        changes.apply("businessValue", story.getBusinessValue(), request.businessValue(),
                story::setBusinessValue);
        changes.apply("dueDate", story.getDueDate(), request.dueDate(), story::setDueDate);
        changes.apply("requirementId", story.getRequirementId(), request.requirementId(),
                story::setRequirementId);

        if (request.epicId() != null && !request.epicId().equals(story.getEpicId())) {
            epicService.requireInProject(request.epicId(), story.getProjectId());
            changes.record("epicId", story.getEpicId(), request.epicId());
            story.setEpicId(request.epicId());
        }
        if (request.readyChecklist() != null) {
            story.setReadyChecklist(copy(request.readyChecklist()));
            changes.record("readyChecklist", null, request.readyChecklist().size() + " item(s)");
        }
        if (request.doneChecklist() != null) {
            story.setDoneChecklist(copy(request.doneChecklist()));
            changes.record("doneChecklist", null, request.doneChecklist().size() + " item(s)");
        }

        if (changes.isEmpty()) {
            return toResponse(story);
        }
        story = storyRepository.save(story);
        activityService.recordChanges(target(story), changes);
        return toResponse(story);
    }

    /**
     * Moves a story through the workflow. Both representations move together: the
     * caller may name a board column, a status, or both, and whichever is missing is
     * derived from the other.
     */
    @Transactional
    public StoryResponse changeStatus(UUID storyId, UpdateStoryStatusRequest request) {
        Story story = require(storyId);
        StoryStatus previous = story.getStatus();
        UUID previousStateId = story.getWorkflowStateId();

        if (request.workflowStateId() != null) {
            WorkflowStateView state = workflowStateService.requireView(story.getProjectId(),
                    request.workflowStateId());
            story.setWorkflowStateId(state.id());
        } else {
            resolveStateForStatus(story.getProjectId(), request.status())
                    .map(WorkflowStateView::id)
                    .ifPresent(story::setWorkflowStateId);
        }

        story.transitionTo(request.status());
        // Leaving a sprint-committed story blocked while it moves to Done would keep it
        // on the impediment board forever; completing work resolves the blocker.
        if (request.status().isTerminal() && story.isBlocked()) {
            story.setBlocked(false);
            story.setBlockedReason(null);
        }
        story = storyRepository.save(story);

        if (previous != request.status()) {
            activityService.recordChange(target(story), ActivityAction.STATUS_CHANGED, "status",
                    previous, request.status(),
                    "%s moved from %s to %s".formatted(story.getItemKey(), previous, request.status()));
        } else if (!Objects.equals(previousStateId, story.getWorkflowStateId())) {
            activityService.recordChange(target(story), ActivityAction.STATUS_CHANGED, "workflowStateId",
                    previousStateId, story.getWorkflowStateId(),
                    "%s moved to another board column".formatted(story.getItemKey()));
        }
        return toResponse(story);
    }

    @Transactional
    public StoryResponse assign(UUID storyId, AssignStoryRequest request) {
        Story story = require(storyId);
        UUID previous = story.getAssigneeId();
        if (Objects.equals(previous, request.assigneeId())) {
            return toResponse(story);
        }
        if (request.assigneeId() != null) {
            userService.requireActiveMember(request.assigneeId());
        }
        story.setAssigneeId(request.assigneeId());
        story = storyRepository.save(story);

        activityService.recordChange(target(story),
                request.assigneeId() == null ? ActivityAction.UNASSIGNED : ActivityAction.ASSIGNED,
                "assigneeId", previous, request.assigneeId(),
                request.assigneeId() == null
                        ? "%s unassigned".formatted(story.getItemKey())
                        : "%s assigned".formatted(story.getItemKey()));
        return toResponse(story);
    }

    /**
     * Sets the point estimate and records where the number came from.
     *
     * <p>The AI's own suggestion is never overwritten here — only the human's number
     * and the source flag change. Keeping both is what lets the UI show "AI said 5, team
     * chose 8" after the fact, and it is the entire training signal for M1.</p>
     */
    @Transactional
    public StoryResponse estimate(UUID storyId, EstimateStoryRequest request) {
        Story story = require(storyId);
        BigDecimal previous = story.getStoryPoints();
        EstimateSource source = request.source() == null ? EstimateSource.MANUAL : request.source();

        if (source == EstimateSource.AI_ACCEPTED && story.getAiStoryPoints() == null
                && request.predictionId() == null) {
            throw new ConflictException(
                    "Cannot accept an AI estimate for %s: no prediction is attached to this story"
                            .formatted(story.getItemKey()));
        }

        story.setStoryPoints(request.storyPoints());
        story.setEstimateSource(source);
        if (request.predictionId() != null) {
            story.setAiPredictionId(request.predictionId());
        }
        story = storyRepository.save(story);

        activityService.recordChange(target(story), ActivityAction.ESTIMATED, "storyPoints",
                previous, request.storyPoints(),
                "%s estimated at %s point(s) (%s)".formatted(story.getItemKey(), request.storyPoints(), source));
        return toResponse(story);
    }

    @Transactional
    public StoryResponse block(UUID storyId, BlockStoryRequest request) {
        Story story = require(storyId);
        story.setBlocked(true);
        story.setBlockedReason(request.reason().trim());
        story = storyRepository.save(story);

        activityService.record(target(story), ActivityAction.BLOCKED,
                "%s blocked: %s".formatted(story.getItemKey(), request.reason().trim()));
        return toResponse(story);
    }

    @Transactional
    public StoryResponse unblock(UUID storyId) {
        Story story = require(storyId);
        if (!story.isBlocked()) {
            return toResponse(story);
        }
        story.setBlocked(false);
        story.setBlockedReason(null);
        story = storyRepository.save(story);

        activityService.record(target(story), ActivityAction.UNBLOCKED,
                "%s unblocked".formatted(story.getItemKey()));
        return toResponse(story);
    }

    @Transactional
    public List<LabelResponse> applyLabels(UUID storyId, List<UUID> labelIds) {
        Story story = require(storyId);
        List<LabelResponse> labels = labelService.apply(EntityType.STORY, storyId, labelIds);
        activityService.record(target(story), ActivityAction.LABEL_ADDED,
                "%s now has %d label(s)".formatted(story.getItemKey(), labels.size()));
        return labels;
    }

    @Transactional
    public void delete(UUID storyId) {
        Story story = require(storyId);
        story.setDeletedAt(Instant.now());
        storyRepository.save(story);
        activityService.recordDeleted(target(story),
                "%s moved to the recycle bin".formatted(story.getItemKey()));
    }

    @Transactional
    public StoryResponse restore(UUID storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNotNull(storyId)
                .orElseThrow(() -> new ResourceNotFoundException("Deleted story", storyId));
        story.setDeletedAt(null);
        story = storyRepository.save(story);
        activityService.record(target(story), ActivityAction.RESTORED,
                "%s restored".formatted(story.getItemKey()));
        return toResponse(story);
    }

    // ------------------------------------------------------------------ internals

    Story require(UUID storyId) {
        return storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", storyId));
    }

    /** First board column whose category matches the target status. */
    private Optional<WorkflowStateView> resolveStateForStatus(UUID projectId, StoryStatus status) {
        String category = switch (status) {
            case BACKLOG, READY -> "TODO";
            case IN_PROGRESS, IN_REVIEW, TESTING -> "IN_PROGRESS";
            case DONE -> "DONE";
            case CANCELLED -> "CANCELLED";
        };
        return workflowStateService.viewsFor(projectId).stream()
                .filter(state -> state.category().equals(category))
                .findFirst();
    }

    private String nextRank(UUID projectId) {
        return storyRepository.findMaxRank(projectId).map(LexoRank::after).orElse(LexoRank.INITIAL);
    }

    private ActivityService.Target target(Story story) {
        return ActivityService.Target.of(EntityType.STORY, story.getId(), story.getItemKey(),
                story.getProjectId());
    }

    private static List<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static HistoryEntryResponse toHistoryEntry(ActivityLog log) {
        return new HistoryEntryResponse(log.getId(), log.getAction(), log.getFieldName(), log.getOldValue(),
                log.getNewValue(), log.getSummary(), log.getActorId(), log.getCreatedAt());
    }

    // ------------------------------------------------------------------ mapping

    StoryResponse toResponse(Story story) {
        return toResponse(story,
                labelService.labelsFor(EntityType.STORY, story.getId()),
                story.getAssigneeId() == null ? null
                        : userService.summariesFor(List.of(story.getAssigneeId())).get(story.getAssigneeId()));
    }

    /**
     * Builds the response functions for a whole page in two extra queries rather than
     * two per row. The backlog is the most-read screen in the product and routinely
     * shows a hundred stories; per-row lookups here would dominate its latency.
     */
    java.util.function.Function<Story, StoryResponse> enrichmentFor(List<Story> stories) {
        List<UUID> storyIds = stories.stream().map(Story::getId).toList();
        Map<UUID, List<LabelResponse>> labels = labelService.labelsFor(EntityType.STORY, storyIds);
        Map<UUID, UserSummary> assignees = userService.summariesFor(
                stories.stream().map(Story::getAssigneeId).filter(Objects::nonNull).toList());

        return story -> toResponse(story,
                labels.getOrDefault(story.getId(), List.of()),
                story.getAssigneeId() == null ? null : assignees.get(story.getAssigneeId()));
    }

    private StoryResponse toResponse(Story story, List<LabelResponse> labels, UserSummary assignee) {
        return new StoryResponse(
                story.getId(), story.getProjectId(), story.getEpicId(), story.getSprintId(),
                story.getRequirementId(), story.getWorkflowStateId(), story.getItemKey(), story.getTitle(),
                story.getDescription(), story.getAcceptanceCriteria(), story.getType(), story.getStatus(),
                story.getPriority(), story.getSeverity(), story.getStoryPoints(),
                AiEstimate.of(story.getAiStoryPoints(), story.getAiConfidence(), story.getAiLowerBound(),
                        story.getAiUpperBound(), story.getAiPredictionId()),
                story.getEstimateSource(), story.getBusinessValue(), story.getRank(), story.getAssigneeId(),
                assignee == null ? null : assignee.fullName(),
                assignee == null ? null : assignee.avatarUrl(),
                story.getReporterId(), story.getDueDate(), story.isBlocked(), story.getBlockedReason(),
                story.getReopenedCount(), story.getReadyChecklist(), story.getDoneChecklist(), labels,
                story.getStartedAt(), story.getCompletedAt(), story.getCreatedAt(), story.getUpdatedAt());
    }
}
