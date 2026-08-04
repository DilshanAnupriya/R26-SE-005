package com.sprintiq.pms.modules.sprints.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.sprints.application.*;
import com.sprintiq.pms.modules.sprints.domain.SprintStatus;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Sprints", description = "Sprint lifecycle, capacity, board, burndown and retrospectives")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/sprints")
@RequiredArgsConstructor
public class SprintController {

    private final SprintService sprintService;
    private final SprintCapacityService capacityService;
    private final SprintMetricsService metricsService;
    private final SprintBoardService boardService;
    private final RetrospectiveService retrospectiveService;

    // ---------------------------------------------------------------- lifecycle

    @Operation(summary = "List sprints")
    @PreAuthorize("@perm.has('sprint:read')")
    @GetMapping
    public PageResponse<SprintResponse> list(@RequestParam(required = false) UUID projectId,
                                             @RequestParam(required = false) SprintStatus status,
                                             @Valid PageQuery query) {
        return sprintService.search(projectId, status, query);
    }

    @Operation(summary = "Get one sprint")
    @PreAuthorize("@perm.has('sprint:read')")
    @GetMapping("/{sprintId}")
    public SprintResponse get(@PathVariable UUID sprintId) {
        return sprintService.get(sprintId);
    }

    @Operation(summary = "Create a sprint")
    @PreAuthorize("@perm.has('sprint:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SprintResponse create(@Valid @RequestBody CreateSprintRequest request) {
        return sprintService.create(request);
    }

    @Operation(summary = "Update a sprint")
    @PreAuthorize("@perm.has('sprint:manage')")
    @PatchMapping("/{sprintId}")
    public SprintResponse update(@PathVariable UUID sprintId,
                                 @Valid @RequestBody UpdateSprintRequest request) {
        return sprintService.update(sprintId, request);
    }

    @Operation(summary = "Start the sprint and freeze its commitment",
            description = "Captures committedPoints from the sprint's current contents. "
                    + "Only one sprint per project can be active.")
    @PreAuthorize("@perm.has('sprint:manage')")
    @PostMapping("/{sprintId}/start")
    public SprintResponse start(@PathVariable UUID sprintId) {
        return sprintService.start(sprintId);
    }

    @Operation(summary = "Complete the sprint and handle spillover")
    @PreAuthorize("@perm.has('sprint:manage')")
    @PostMapping("/{sprintId}/complete")
    public CompleteSprintResponse complete(@PathVariable UUID sprintId,
                                           @Valid @RequestBody CompleteSprintRequest request) {
        return sprintService.complete(sprintId, request);
    }

    @Operation(summary = "Cancel the sprint; its items return to the backlog")
    @PreAuthorize("@perm.has('sprint:manage')")
    @PostMapping("/{sprintId}/cancel")
    public SprintResponse cancel(@PathVariable UUID sprintId) {
        return sprintService.cancel(sprintId);
    }

    @Operation(summary = "Delete a sprint")
    @PreAuthorize("@perm.has('sprint:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{sprintId}")
    public void delete(@PathVariable UUID sprintId) {
        sprintService.delete(sprintId);
    }

    // -------------------------------------------------------------------- items

    @Operation(summary = "Stories committed to the sprint")
    @PreAuthorize("@perm.has('sprint:read')")
    @GetMapping("/{sprintId}/items")
    public List<StoryResponse> items(@PathVariable UUID sprintId) {
        return sprintService.items(sprintId);
    }

    @Operation(summary = "Commit stories to the sprint",
            description = "Points added after the sprint has started are recorded as scope creep.")
    @PreAuthorize("@perm.has('sprint:manage')")
    @PostMapping("/{sprintId}/items")
    public List<StoryResponse> addItems(@PathVariable UUID sprintId,
                                        @Valid @RequestBody AddSprintItemsRequest request) {
        return sprintService.addItems(sprintId, request);
    }

    @Operation(summary = "Return a story to the backlog")
    @PreAuthorize("@perm.has('sprint:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{sprintId}/items/{storyId}")
    public void removeItem(@PathVariable UUID sprintId, @PathVariable UUID storyId) {
        sprintService.removeItem(sprintId, storyId);
    }

    // -------------------------------------------------------------- board & data

    @Operation(summary = "Sprint board grouped by workflow state")
    @PreAuthorize("@perm.has('sprint:read')")
    @GetMapping("/{sprintId}/board")
    public SprintBoardResponse board(@PathVariable UUID sprintId) {
        return boardService.board(sprintId);
    }

    @Operation(summary = "Capacity plan and the planning capacity meter")
    @PreAuthorize("@perm.has('sprint:read')")
    @GetMapping("/{sprintId}/capacity")
    public SprintCapacityResponse capacity(@PathVariable UUID sprintId) {
        return capacityService.get(sprintId);
    }

    @Operation(summary = "Replace the sprint's capacity plan")
    @PreAuthorize("@perm.has('sprint:manage')")
    @PutMapping("/{sprintId}/capacity")
    public SprintCapacityResponse updateCapacity(@PathVariable UUID sprintId,
                                                 @Valid @RequestBody UpdateCapacityRequest request) {
        return capacityService.update(sprintId, request);
    }

    @Operation(summary = "Burndown series from recorded daily snapshots")
    @PreAuthorize("@perm.has('sprint:read')")
    @GetMapping("/{sprintId}/burndown")
    public BurndownResponse burndown(@PathVariable UUID sprintId) {
        return metricsService.burndown(sprintId);
    }

    @Operation(summary = "Sprint metrics roll-up")
    @PreAuthorize("@perm.has('sprint:read')")
    @GetMapping("/{sprintId}/metrics")
    public SprintMetricsResponse metrics(@PathVariable UUID sprintId) {
        return metricsService.metrics(sprintId);
    }

    // ------------------------------------------------------------ retrospective

    @Operation(summary = "Get the sprint retrospective")
    @PreAuthorize("@perm.has('sprint:read')")
    @GetMapping("/{sprintId}/retrospective")
    public RetrospectiveResponse retrospective(@PathVariable UUID sprintId) {
        return retrospectiveService.get(sprintId);
    }

    @Operation(summary = "Create or update the sprint retrospective")
    @PreAuthorize("@perm.has('sprint:manage')")
    @PostMapping("/{sprintId}/retrospective")
    public RetrospectiveResponse saveRetrospective(@PathVariable UUID sprintId,
                                                   @Valid @RequestBody RetrospectiveRequest request) {
        return retrospectiveService.save(sprintId, request);
    }
}
