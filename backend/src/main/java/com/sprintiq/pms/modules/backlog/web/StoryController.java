package com.sprintiq.pms.modules.backlog.web;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.backlog.application.DependencyService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.backlog.domain.StoryType;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.ApplyLabelsRequest;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.LabelResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Stories", description = "Backlog items: create, estimate, assign, move and block")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/stories")
@RequiredArgsConstructor
public class StoryController {

    private final StoryService storyService;
    private final DependencyService dependencyService;

    @Operation(summary = "Search stories")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping
    public PageResponse<StoryResponse> list(@RequestParam UUID projectId,
                                            @RequestParam(required = false) UUID epicId,
                                            @RequestParam(required = false) StoryStatus status,
                                            @RequestParam(required = false) StoryType type,
                                            @RequestParam(required = false) Priority priority,
                                            @RequestParam(required = false) UUID assigneeId,
                                            @RequestParam(required = false) UUID sprintId,
                                            @RequestParam(defaultValue = "false") boolean backlogOnly,
                                            @Valid PageQuery query) {
        return storyService.search(projectId, epicId, status, type, priority, assigneeId, sprintId,
                backlogOnly, query);
    }

    @Operation(summary = "Get one story")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping("/{storyId}")
    public StoryResponse get(@PathVariable UUID storyId) {
        return storyService.get(storyId);
    }

    @Operation(summary = "Field-level change history")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping("/{storyId}/history")
    public PageResponse<HistoryEntryResponse> history(@PathVariable UUID storyId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return storyService.history(storyId, PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Operation(summary = "Create a story")
    @PreAuthorize("@perm.has('backlog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public StoryResponse create(@Valid @RequestBody CreateStoryRequest request) {
        return storyService.create(request);
    }

    @Operation(summary = "Update a story")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PatchMapping("/{storyId}")
    public StoryResponse update(@PathVariable UUID storyId, @Valid @RequestBody UpdateStoryRequest request) {
        return storyService.update(storyId, request);
    }

    @Operation(summary = "Move a story to another status or board column")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PatchMapping("/{storyId}/status")
    public StoryResponse changeStatus(@PathVariable UUID storyId,
                                      @Valid @RequestBody UpdateStoryStatusRequest request) {
        return storyService.changeStatus(storyId, request);
    }

    @Operation(summary = "Assign or unassign a story")
    @PreAuthorize("@perm.has('task:assign')")
    @PatchMapping("/{storyId}/assign")
    public StoryResponse assign(@PathVariable UUID storyId, @Valid @RequestBody AssignStoryRequest request) {
        return storyService.assign(storyId, request);
    }

    @Operation(summary = "Set the point estimate and record whether it accepted or overrode the AI")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PatchMapping("/{storyId}/estimate")
    public StoryResponse estimate(@PathVariable UUID storyId,
                                  @Valid @RequestBody EstimateStoryRequest request) {
        return storyService.estimate(storyId, request);
    }

    @Operation(summary = "Flag a story as blocked")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PostMapping("/{storyId}/block")
    public StoryResponse block(@PathVariable UUID storyId, @Valid @RequestBody BlockStoryRequest request) {
        return storyService.block(storyId, request);
    }

    @Operation(summary = "Clear the blocked flag")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PostMapping("/{storyId}/unblock")
    public StoryResponse unblock(@PathVariable UUID storyId) {
        return storyService.unblock(storyId);
    }

    @Operation(summary = "Replace a story's labels")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PostMapping("/{storyId}/labels")
    public List<LabelResponse> applyLabels(@PathVariable UUID storyId,
                                           @Valid @RequestBody ApplyLabelsRequest request) {
        return storyService.applyLabels(storyId, request.labelIds());
    }

    @Operation(summary = "Move a story to the recycle bin")
    @PreAuthorize("@perm.has('backlog:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{storyId}")
    public void delete(@PathVariable UUID storyId) {
        storyService.delete(storyId);
    }

    @Operation(summary = "Restore a soft-deleted story")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PostMapping("/{storyId}/restore")
    public StoryResponse restore(@PathVariable UUID storyId) {
        return storyService.restore(storyId);
    }

    // ------------------------------------------------------------ dependencies

    @Operation(summary = "Dependencies touching this story, in both directions")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping("/{storyId}/dependencies")
    public List<DependencyResponse> dependencies(@PathVariable UUID storyId) {
        return dependencyService.list(EntityType.STORY, storyId);
    }

    @Operation(summary = "Add a dependency; blocking edges are rejected if they would form a cycle")
    @PreAuthorize("@perm.has('backlog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{storyId}/dependencies")
    public DependencyResponse addDependency(@PathVariable UUID storyId,
                                            @Valid @RequestBody CreateDependencyRequest request) {
        return dependencyService.create(EntityType.STORY, storyId, request);
    }

    @Operation(summary = "Remove a dependency")
    @PreAuthorize("@perm.has('backlog:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/dependencies/{dependencyId}")
    public void removeDependency(@PathVariable UUID dependencyId) {
        dependencyService.delete(dependencyId);
    }
}
