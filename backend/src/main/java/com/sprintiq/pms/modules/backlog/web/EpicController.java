package com.sprintiq.pms.modules.backlog.web;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.backlog.application.EpicService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.EpicStatus;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Epics", description = "Epics grouping stories under one outcome")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/epics")
@RequiredArgsConstructor
public class EpicController {

    private final EpicService epicService;
    private final StoryService storyService;

    @Operation(summary = "Search epics in a project")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping
    public PageResponse<EpicResponse> list(@RequestParam UUID projectId,
                                           @RequestParam(required = false) EpicStatus status,
                                           @RequestParam(required = false) Priority priority,
                                           @RequestParam(required = false) UUID milestoneId,
                                           @Valid PageQuery query) {
        return epicService.search(projectId, status, priority, milestoneId, query);
    }

    @Operation(summary = "Get one epic")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping("/{epicId}")
    public EpicResponse get(@PathVariable UUID epicId) {
        return epicService.get(epicId);
    }

    @Operation(summary = "Stories under an epic")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping("/{epicId}/stories")
    public PageResponse<StoryResponse> stories(@PathVariable UUID epicId, @Valid PageQuery query) {
        EpicResponse epic = epicService.get(epicId);
        return storyService.search(epic.projectId(), epicId, null, null, null, null, null, false, query);
    }

    @Operation(summary = "Create an epic")
    @PreAuthorize("@perm.has('backlog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public EpicResponse create(@Valid @RequestBody CreateEpicRequest request) {
        return epicService.create(request);
    }

    @Operation(summary = "Update an epic")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PatchMapping("/{epicId}")
    public EpicResponse update(@PathVariable UUID epicId, @Valid @RequestBody UpdateEpicRequest request) {
        return epicService.update(epicId, request);
    }

    @Operation(summary = "Delete an epic; its stories return to the backlog")
    @PreAuthorize("@perm.has('backlog:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{epicId}")
    public void delete(@PathVariable UUID epicId) {
        epicService.delete(epicId);
    }
}
