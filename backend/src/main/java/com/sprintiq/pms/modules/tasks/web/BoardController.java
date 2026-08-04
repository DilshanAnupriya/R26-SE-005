package com.sprintiq.pms.modules.tasks.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.tasks.application.BoardService;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.TaskBoardResponse;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.WorkloadEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Boards", description = "Project kanban board and the workload planner")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/projects/{projectId}")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    @Operation(summary = "Project kanban board, grouped by workflow state",
            description = "Shows tasks. For the story-level sprint board use GET /sprints/{id}/board.")
    @PreAuthorize("@perm.has('task:read')")
    @GetMapping("/board")
    public TaskBoardResponse board(@PathVariable UUID projectId,
                                   @RequestParam(required = false) UUID sprintId,
                                   @RequestParam(required = false) UUID assigneeId) {
        return boardService.board(projectId, sprintId, assigneeId);
    }

    @Operation(summary = "Open work per project member, with over-allocation flags")
    @PreAuthorize("@perm.has('task:read')")
    @GetMapping("/workload")
    public List<WorkloadEntryResponse> workload(@PathVariable UUID projectId) {
        return boardService.workload(projectId);
    }
}
