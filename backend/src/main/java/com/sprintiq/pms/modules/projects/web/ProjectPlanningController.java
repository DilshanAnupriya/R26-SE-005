package com.sprintiq.pms.modules.projects.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.projects.application.MilestoneService;
import com.sprintiq.pms.modules.projects.application.RequirementService;
import com.sprintiq.pms.modules.projects.domain.RequirementStatus;
import com.sprintiq.pms.modules.projects.domain.RequirementType;
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

/**
 * Milestones and requirements.
 *
 * <p>Both are addressed two ways, matching the API map: nested under a project when
 * listing or creating (the project is the scope), and flat by id when updating or
 * deleting (the id is already unique, and nesting would let a client pair a milestone
 * with the wrong project in the URL).</p>
 */
@Tag(name = "Project planning", description = "Milestones and the requirements register")
@RestController
@RequestMapping(ApiConstants.API_V1)
@RequiredArgsConstructor
public class ProjectPlanningController {

    private final MilestoneService milestoneService;
    private final RequirementService requirementService;

    // ------------------------------------------------------------------ milestones

    @Operation(summary = "List project milestones")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/projects/{projectId}/milestones")
    public List<MilestoneResponse> listMilestones(@PathVariable UUID projectId) {
        return milestoneService.list(projectId);
    }

    @Operation(summary = "Create a milestone")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/projects/{projectId}/milestones")
    public MilestoneResponse createMilestone(@PathVariable UUID projectId,
                                             @Valid @RequestBody CreateMilestoneRequest request) {
        return milestoneService.create(projectId, request);
    }

    @Operation(summary = "Get one milestone")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/milestones/{milestoneId}")
    public MilestoneResponse getMilestone(@PathVariable UUID milestoneId) {
        return milestoneService.get(milestoneId);
    }

    @Operation(summary = "Update a milestone")
    @PreAuthorize("@perm.has('project:update')")
    @PatchMapping("/milestones/{milestoneId}")
    public MilestoneResponse updateMilestone(@PathVariable UUID milestoneId,
                                             @Valid @RequestBody UpdateMilestoneRequest request) {
        return milestoneService.update(milestoneId, request);
    }

    @Operation(summary = "Delete a milestone")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/milestones/{milestoneId}")
    public void deleteMilestone(@PathVariable UUID milestoneId) {
        milestoneService.delete(milestoneId);
    }

    // ---------------------------------------------------------------- requirements

    @Operation(summary = "Search the project's requirements register")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/projects/{projectId}/requirements")
    public PageResponse<RequirementResponse> listRequirements(
            @PathVariable UUID projectId,
            @RequestParam(required = false) RequirementStatus status,
            @RequestParam(required = false) RequirementType type,
            @Valid PageQuery query) {
        return requirementService.search(projectId, status, type, query);
    }

    @Operation(summary = "Create a requirement")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/projects/{projectId}/requirements")
    public RequirementResponse createRequirement(@PathVariable UUID projectId,
                                                 @Valid @RequestBody CreateRequirementRequest request) {
        return requirementService.create(projectId, request);
    }

    @Operation(summary = "Get one requirement")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/requirements/{requirementId}")
    public RequirementResponse getRequirement(@PathVariable UUID requirementId) {
        return requirementService.get(requirementId);
    }

    @Operation(summary = "Update a requirement (counts as churn once approved)")
    @PreAuthorize("@perm.has('project:update')")
    @PatchMapping("/requirements/{requirementId}")
    public RequirementResponse updateRequirement(@PathVariable UUID requirementId,
                                                 @Valid @RequestBody UpdateRequirementRequest request) {
        return requirementService.update(requirementId, request);
    }

    @Operation(summary = "Delete a requirement")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/requirements/{requirementId}")
    public void deleteRequirement(@PathVariable UUID requirementId) {
        requirementService.delete(requirementId);
    }
}
