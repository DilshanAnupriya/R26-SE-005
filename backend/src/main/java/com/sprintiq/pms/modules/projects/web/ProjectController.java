package com.sprintiq.pms.modules.projects.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.domain.ProjectHealth;
import com.sprintiq.pms.modules.projects.domain.ProjectStatus;
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

@Tag(name = "Projects", description = "Delivery projects, membership and health")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "List projects")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping
    public PageResponse<ProjectResponse> list(@RequestParam(required = false) UUID workspaceId,
                                              @RequestParam(required = false) ProjectStatus status,
                                              @RequestParam(required = false) ProjectHealth health,
                                              @RequestParam(required = false) UUID leadId,
                                              @Valid PageQuery query) {
        return projectService.search(workspaceId, status, health, leadId, query);
    }

    @Operation(summary = "Get one project")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable UUID projectId) {
        return projectService.get(projectId);
    }

    @Operation(summary = "Project overview with delivery roll-up")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/{projectId}/overview")
    public ProjectOverviewResponse overview(@PathVariable UUID projectId) {
        return projectService.overview(projectId);
    }

    @Operation(summary = "Project health with contributing signals")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/{projectId}/health")
    public ProjectHealthResponse health(@PathVariable UUID projectId) {
        return projectService.health(projectId);
    }

    @Operation(summary = "Create a project")
    @PreAuthorize("@perm.has('project:create')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(request);
    }

    @Operation(summary = "Update a project")
    @PreAuthorize("@perm.has('project:update')")
    @PatchMapping("/{projectId}")
    public ProjectResponse update(@PathVariable UUID projectId,
                                  @Valid @RequestBody UpdateProjectRequest request) {
        return projectService.update(projectId, request);
    }

    @Operation(summary = "Move a project to the recycle bin")
    @PreAuthorize("@perm.has('project:delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{projectId}")
    public void delete(@PathVariable UUID projectId) {
        projectService.delete(projectId);
    }

    @Operation(summary = "Restore a soft-deleted project")
    @PreAuthorize("@perm.has('project:delete')")
    @PostMapping("/{projectId}/restore")
    public ProjectResponse restore(@PathVariable UUID projectId) {
        return projectService.restore(projectId);
    }

    // ------------------------------------------------------------------ members

    @Operation(summary = "List project members")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/{projectId}/members")
    public List<ProjectMemberResponse> listMembers(@PathVariable UUID projectId) {
        return projectService.listMembers(projectId);
    }

    @Operation(summary = "Add a member to the project")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{projectId}/members")
    public ProjectMemberResponse addMember(@PathVariable UUID projectId,
                                           @Valid @RequestBody AddProjectMemberRequest request) {
        return projectService.addMember(projectId, request);
    }

    @Operation(summary = "Change a member's project role or allocation")
    @PreAuthorize("@perm.has('project:update')")
    @PatchMapping("/{projectId}/members/{userId}")
    public ProjectMemberResponse updateMember(@PathVariable UUID projectId,
                                              @PathVariable UUID userId,
                                              @Valid @RequestBody UpdateProjectMemberRequest request) {
        return projectService.updateMember(projectId, userId, request);
    }

    @Operation(summary = "Remove a member from the project")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{projectId}/members/{userId}")
    public void removeMember(@PathVariable UUID projectId, @PathVariable UUID userId) {
        projectService.removeMember(projectId, userId);
    }
}
