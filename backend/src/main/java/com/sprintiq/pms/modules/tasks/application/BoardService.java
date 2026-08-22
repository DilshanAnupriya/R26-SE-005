package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.modules.projects.application.ProjectLookupService;
import com.sprintiq.pms.modules.projects.application.WorkflowStateService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.ProjectMemberResponse;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.WorkflowStateResponse;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.tasks.domain.Task;
import com.sprintiq.pms.modules.tasks.domain.TaskRepository;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * The project kanban board and the workload planner.
 *
 * <p>The board shows tasks — the sprint board in the sprints module shows stories. They
 * are genuinely different views for different audiences (a developer works a task board,
 * a scrum master runs a sprint board), which is why they are two endpoints rather than
 * one with a mode flag.</p>
 */
@Service
@RequiredArgsConstructor
public class BoardService {

    /** Minutes of open, estimated work treated as a full load for one person per sprint. */
    private static final int FULL_LOAD_MINUTES = 60 * 8 * 10;

    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final WorkflowStateService workflowStateService;
    private final ProjectLookupService projectLookup;
    private final ProjectService projectService;
    private final com.sprintiq.pms.modules.backlog.domain.StoryRepository storyRepository;
    private final com.sprintiq.pms.modules.users.application.UserService userService;

    @Transactional(readOnly = true)
    public TaskBoardResponse board(UUID projectId, UUID sprintId, UUID assigneeId) {
        projectLookup.require(projectId);
        List<WorkflowStateResponse> states = workflowStateService.list(projectId);

        PageQuery query = new PageQuery();
        query.setSize(PageQuery.MAX_PAGE_SIZE);
        List<Task> tasks = taskRepository.search(projectId, null, sprintId, assigneeId, null, null, null,
                null, false, null, query.toPageable(TaskService.SORT_FIELDS,
                        org.springframework.data.domain.Sort.by("rank"))).getContent();

        var enrich = taskService.enrichmentFor(tasks);
        Map<UUID, List<TaskResponse>> byState = new LinkedHashMap<>();
        List<TaskResponse> unplaced = new ArrayList<>();
        for (Task task : tasks) {
            TaskResponse response = enrich.apply(task);
            if (task.getWorkflowStateId() == null) {
                unplaced.add(response);
            } else {
                byState.computeIfAbsent(task.getWorkflowStateId(), key -> new ArrayList<>()).add(response);
            }
        }

        // Also include committed stories from this sprint/project
        List<com.sprintiq.pms.modules.backlog.domain.Story> stories;
        if (sprintId != null) {
            stories = storyRepository.findBySprintIdAndDeletedAtIsNull(sprintId);
        } else {
            stories = storyRepository.search(projectId, null, null, null, null, assigneeId, null, false, null,
                    org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
        }

        List<UUID> storyAssigneeIds = stories.stream()
                .map(com.sprintiq.pms.modules.backlog.domain.Story::getAssigneeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, com.sprintiq.pms.modules.users.application.UserSummary> userSummaries =
                storyAssigneeIds.isEmpty() ? Map.of() : userService.summariesFor(storyAssigneeIds);

        for (com.sprintiq.pms.modules.backlog.domain.Story story : stories) {
            if (assigneeId != null && !assigneeId.equals(story.getAssigneeId())) {
                continue;
            }
            boolean hasSubtasks = tasks.stream().anyMatch(t -> story.getId().equals(t.getStoryId()));
            if (hasSubtasks) {
                continue;
            }

            com.sprintiq.pms.modules.tasks.domain.TaskStatus status = switch (story.getStatus()) {
                case IN_PROGRESS -> com.sprintiq.pms.modules.tasks.domain.TaskStatus.IN_PROGRESS;
                case DONE -> com.sprintiq.pms.modules.tasks.domain.TaskStatus.DONE;
                case CANCELLED -> com.sprintiq.pms.modules.tasks.domain.TaskStatus.CANCELLED;
                default -> com.sprintiq.pms.modules.tasks.domain.TaskStatus.TODO;
            };

            int estimateMins = story.getStoryPoints() != null ? story.getStoryPoints().intValue() * 60 : 0;
            var assignee = story.getAssigneeId() != null ? userSummaries.get(story.getAssigneeId()) : null;

            TaskResponse response = new TaskResponse(
                    story.getId(),
                    story.getProjectId(),
                    story.getId(),
                    null,
                    story.getSprintId(),
                    story.getWorkflowStateId(),
                    story.getItemKey(),
                    story.getTitle(),
                    story.getDescription(),
                    com.sprintiq.pms.modules.tasks.domain.TaskType.TASK,
                    status,
                    story.getPriority(),
                    com.sprintiq.pms.common.domain.Severity.MINOR,
                    story.getAssigneeId(),
                    assignee != null ? assignee.fullName() : null,
                    assignee != null ? assignee.avatarUrl() : null,
                    com.sprintiq.pms.modules.tasks.domain.AssignmentSource.MANUAL,
                    null,
                    story.getReporterId(),
                    estimateMins > 0 ? estimateMins : null,
                    0,
                    null,
                    false,
                    BigDecimal.valueOf(3.0),
                    List.of(),
                    null,
                    story.getRank(),
                    story.isBlocked(),
                    story.getBlockedReason(),
                    (short) 0,
                    0,
                    null,
                    null,
                    story.getCreatedAt(),
                    story.getUpdatedAt(),
                    0L,
                    null
            );

            if (story.getWorkflowStateId() == null) {
                unplaced.add(response);
            } else {
                byState.computeIfAbsent(story.getWorkflowStateId(), key -> new ArrayList<>()).add(response);
            }
        }

        List<TaskBoardColumnResponse> columns = new ArrayList<>(states.size());
        for (int index = 0; index < states.size(); index++) {
            WorkflowStateResponse state = states.get(index);
            List<TaskResponse> columnTasks = new ArrayList<>(byState.getOrDefault(state.id(), List.of()));
            if (index == 0 && !unplaced.isEmpty()) {
                columnTasks.addAll(unplaced);
            }
            columnTasks.sort(Comparator.comparing(TaskResponse::rank,
                    Comparator.nullsLast(Comparator.naturalOrder())));

            columns.add(new TaskBoardColumnResponse(state, columnTasks, columnTasks.size(),
                    exceedsWipLimit(state, columnTasks.size())));
        }

        int totalCount = tasks.size() + stories.size();
        return new TaskBoardResponse(projectId, columns, totalCount);
    }

    /**
     * Open work per project member, with an over-allocation flag.
     *
     * <p>Everyone on the project appears, including people with nothing assigned —
     * spotting who has spare capacity is half the point of the view, and only listing
     * people who already have work makes that impossible.</p>
     */
    @Transactional(readOnly = true)
    public List<WorkloadEntryResponse> workload(UUID projectId) {
        projectLookup.require(projectId);
        List<ProjectMemberResponse> members = projectService.listMembers(projectId);

        List<WorkloadEntryResponse> workload = new ArrayList<>(members.size());
        for (ProjectMemberResponse member : members) {
            List<Task> open = taskRepository.findOpenForUser(member.userId()).stream()
                    .filter(task -> task.getProjectId().equals(projectId))
                    .toList();

            int estimated = open.stream()
                    .map(Task::getEstimateMinutes).filter(Objects::nonNull)
                    .mapToInt(Integer::intValue).sum();
            int spent = open.stream().mapToInt(Task::getSpentMinutes).sum();
            long blocked = open.stream().filter(Task::isBlocked).count();

            // Scale the full load by the member's declared allocation: someone at 50%
            // is over-allocated at half the work of someone at 100%.
            int capacity = Math.max(1, FULL_LOAD_MINUTES * member.allocationPct() / 100);
            BigDecimal loadPct = BigDecimal.valueOf(estimated)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(capacity), 2, RoundingMode.HALF_UP);

            workload.add(new WorkloadEntryResponse(member.userId(), member.fullName(), member.avatarUrl(),
                    open.size(), blocked, estimated, spent, loadPct,
                    loadPct.compareTo(BigDecimal.valueOf(100)) > 0));
        }
        workload.sort(Comparator.comparing(WorkloadEntryResponse::loadPct).reversed());
        return workload;
    }

    private static boolean exceedsWipLimit(WorkflowStateResponse state, long itemCount) {
        return state.wipLimit() != null && state.wipLimit() > 0 && itemCount > state.wipLimit();
    }
}
