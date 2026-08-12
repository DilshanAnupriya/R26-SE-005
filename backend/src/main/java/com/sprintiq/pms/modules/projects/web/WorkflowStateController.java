package com.sprintiq.pms.modules.projects.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.projects.application.WorkflowStateService;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Workflow states", description = "Per-project board columns and WIP limits")
@RestController
@RequestMapping(ApiConstants.API_V1)
@RequiredArgsConstructor
public class WorkflowStateController {

    private final WorkflowStateService workflowStateService;

    @Operation(summary = "List a project's workflow states, in board order")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/projects/{projectId}/workflow-states")
    public List<WorkflowStateResponse> list(@PathVariable UUID projectId) {
        return workflowStateService.list(projectId);
    }

    @Operation(summary = "Add a workflow state")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/projects/{projectId}/workflow-states")
    public WorkflowStateResponse create(@PathVariable UUID projectId,
                                        @Valid @RequestBody CreateWorkflowStateRequest request) {
        return workflowStateService.create(projectId, request);
    }

    @Operation(summary = "Reorder every column in one call")
    @PreAuthorize("@perm.has('project:update')")
    @PostMapping("/projects/{projectId}/workflow-states/reorder")
    public List<WorkflowStateResponse> reorder(@PathVariable UUID projectId,
                                               @Valid @RequestBody ReorderWorkflowStatesRequest request) {
        return workflowStateService.reorder(projectId, request);
    }

    @Operation(summary = "Update a workflow state (send wipLimit=0 to clear the limit)")
    @PreAuthorize("@perm.has('project:update')")
    @PatchMapping("/workflow-states/{stateId}")
    public WorkflowStateResponse update(@PathVariable UUID stateId,
                                        @Valid @RequestBody UpdateWorkflowStateRequest request) {
        return workflowStateService.update(stateId, request);
    }

    @Operation(summary = "Delete an empty workflow state")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/workflow-states/{stateId}")
    public void delete(@PathVariable UUID stateId) {
        workflowStateService.delete(stateId);
    }
}
