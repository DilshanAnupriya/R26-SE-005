package com.sprintiq.pms.modules.projects.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.projects.application.LabelService;
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

@Tag(name = "Labels", description = "Project and organization-wide labels")
@RestController
@RequestMapping(ApiConstants.API_V1)
@RequiredArgsConstructor
public class LabelController {

    private final LabelService labelService;

    @Operation(summary = "Labels available to a project (its own plus organization-wide)")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/projects/{projectId}/labels")
    public List<LabelResponse> listForProject(@PathVariable UUID projectId) {
        return labelService.listForProject(projectId);
    }

    @Operation(summary = "Organization-wide labels")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/labels")
    public List<LabelResponse> listGlobal() {
        return labelService.listGlobal();
    }

    @Operation(summary = "Create a label; omit projectId to make it organization-wide")
    @PreAuthorize("@perm.has('backlog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/labels")
    public LabelResponse create(@Valid @RequestBody CreateLabelRequest request) {
        return labelService.create(request);
    }

    @Operation(summary = "Delete a label and detach it from every item")
    @PreAuthorize("@perm.has('backlog:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/labels/{labelId}")
    public void delete(@PathVariable UUID labelId) {
        labelService.delete(labelId);
    }

}
// Applying labels to an item lives on that item's own path (POST /stories/{id}/labels,
// POST /tasks/{id}/labels) rather than on a generic /{entityType}/{entityId}/labels
// route, which would have shadowed every other two-segment path under /api/v1.
