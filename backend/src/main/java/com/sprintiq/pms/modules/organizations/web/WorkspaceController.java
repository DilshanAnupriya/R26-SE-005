package com.sprintiq.pms.modules.organizations.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.organizations.application.OrganizationService;
import com.sprintiq.pms.modules.organizations.web.dto.OrganizationDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Workspaces", description = "Workspaces inside the active organization")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final OrganizationService organizationService;

    @Operation(summary = "List workspaces")
    @PreAuthorize("@perm.has('workspace:read')")
    @GetMapping
    public List<WorkspaceResponse> list() {
        return organizationService.listWorkspaces();
    }

    @Operation(summary = "Get one workspace")
    @PreAuthorize("@perm.has('workspace:read')")
    @GetMapping("/{workspaceId}")
    public WorkspaceResponse get(@PathVariable UUID workspaceId) {
        return organizationService.getWorkspace(workspaceId);
    }

    @Operation(summary = "Create a workspace")
    @PreAuthorize("@perm.has('workspace:create')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public WorkspaceResponse create(@Valid @RequestBody CreateWorkspaceRequest request) {
        return organizationService.createWorkspace(request);
    }

    @Operation(summary = "Update a workspace")
    @PreAuthorize("@perm.has('workspace:update')")
    @PatchMapping("/{workspaceId}")
    public WorkspaceResponse update(@PathVariable UUID workspaceId,
                                    @Valid @RequestBody UpdateWorkspaceRequest request) {
        return organizationService.updateWorkspace(workspaceId, request);
    }

    @Operation(summary = "Delete a workspace (soft)")
    @PreAuthorize("@perm.has('workspace:delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{workspaceId}")
    public void delete(@PathVariable UUID workspaceId) {
        organizationService.deleteWorkspace(workspaceId);
    }
}
