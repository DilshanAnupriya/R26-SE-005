package com.sprintiq.pms.modules.sprints.application;

import com.sprintiq.pms.modules.backlog.application.SprintBacklogService;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.projects.application.WorkflowStateService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.WorkflowStateResponse;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Assembles the sprint board: the project's columns, each holding its stories.
 *
 * <p>Built in two reads — the columns, then every story in the sprint — and grouped in
 * memory. Querying per column would be one round trip per column for a board that is
 * refreshed constantly during a standup.</p>
 */
@Service
@RequiredArgsConstructor
public class SprintBoardService {

    private final SprintService sprintService;
    private final SprintBacklogService sprintBacklog;
    private final WorkflowStateService workflowStateService;

    @Transactional(readOnly = true)
    public SprintBoardResponse board(UUID sprintId) {
        Sprint sprint = sprintService.require(sprintId);
        List<WorkflowStateResponse> states = workflowStateService.list(sprint.getProjectId());
        List<StoryResponse> stories = sprintBacklog.storiesIn(sprintId);

        Map<UUID, List<StoryResponse>> byState = new LinkedHashMap<>();
        List<StoryResponse> unplaced = new ArrayList<>();
        for (StoryResponse story : stories) {
            if (story.workflowStateId() == null) {
                unplaced.add(story);
            } else {
                byState.computeIfAbsent(story.workflowStateId(), key -> new ArrayList<>()).add(story);
            }
        }

        List<BoardColumnResponse> columns = new ArrayList<>(states.size());
        for (int index = 0; index < states.size(); index++) {
            WorkflowStateResponse state = states.get(index);
            List<StoryResponse> columnStories =
                    new ArrayList<>(byState.getOrDefault(state.id(), List.of()));

            // A story whose column was deleted would otherwise vanish from the board
            // entirely. Showing it in the first column keeps it reachable and visibly wrong.
            if (index == 0 && !unplaced.isEmpty()) {
                columnStories.addAll(unplaced);
            }
            columnStories.sort(Comparator.comparing(StoryResponse::rank,
                    Comparator.nullsLast(Comparator.naturalOrder())));

            columns.add(new BoardColumnResponse(state, columnStories, columnStories.size(),
                    exceedsWipLimit(state, columnStories.size())));
        }

        return new SprintBoardResponse(sprintId, sprint.getName(), sprint.getStatus(), columns,
                stories.size());
    }

    /** Reported, not enforced — see {@code WorkflowState} for why the limit is advisory. */
    private static boolean exceedsWipLimit(WorkflowStateResponse state, long itemCount) {
        return state.wipLimit() != null && state.wipLimit() > 0 && itemCount > state.wipLimit();
    }
}
