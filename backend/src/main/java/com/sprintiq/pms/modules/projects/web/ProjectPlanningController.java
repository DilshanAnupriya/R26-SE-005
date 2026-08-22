package com.sprintiq.pms.modules.projects.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.projects.application.MilestoneService;
import com.sprintiq.pms.modules.projects.application.RequirementChangeService;
import com.sprintiq.pms.modules.projects.application.RequirementService;
import com.sprintiq.pms.modules.projects.domain.RequirementStatus;
import com.sprintiq.pms.modules.projects.domain.RequirementType;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.*;
import com.sprintiq.pms.modules.projects.web.dto.RequirementChangeDtos.*;
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
    private final RequirementChangeService requirementChangeService;

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

    // --------------------------------------------------------- requirement changes

    @Operation(summary = "What each kind of requirement change costs the sprint's risk",
            description = "Served rather than duplicated in the client so the form and the "
                    + "ledger can never disagree about a weight.")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/requirement-changes/types")
    public List<RequirementChangeTypeInfo> requirementChangeTypes() {
        return requirementChangeService.catalogue();
    }

    @Operation(summary = "The project's requirement-change register")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/projects/{projectId}/requirement-changes")
    public List<RequirementChangeResponse> listRequirementChanges(@PathVariable UUID projectId) {
        return requirementChangeService.listForProject(projectId);
    }

    @Operation(summary = "Log a requirement change",
            description = "Records the change, bumps the requirement's churn counter when one is "
                    + "named, and charges the in-flight sprint the points the change is worth.")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/projects/{projectId}/requirement-changes")
    public RequirementChangeResponse logRequirementChange(
            @PathVariable UUID projectId,
            @Valid @RequestBody LogRequirementChangeRequest request) {
        return requirementChangeService.log(projectId, request);
    }

    @Operation(summary = "Requirement changes logged against one sprint, with churn totals")
    @PreAuthorize("@perm.has('sprint:read')")
    @GetMapping("/sprints/{sprintId}/requirement-changes")
    public RequirementChangeSummary sprintRequirementChanges(@PathVariable UUID sprintId) {
        return requirementChangeService.listForSprint(sprintId);
    }

    @Operation(summary = "Withdraw a logged requirement change, reversing its risk charge")
    @PreAuthorize("@perm.has('project:update')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/requirement-changes/{changeId}")
    public void deleteRequirementChange(@PathVariable UUID changeId) {
        requirementChangeService.delete(changeId);
    }
}
