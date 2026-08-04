package com.sprintiq.pms.modules.backlog.web;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.backlog.application.BacklogService;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.StoryStatus;
import com.sprintiq.pms.modules.backlog.domain.StoryType;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Backlog", description = "Ordered backlog, drag-to-rank and bulk grooming")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/projects/{projectId}/backlog")
@RequiredArgsConstructor
public class BacklogController {

    private final BacklogService backlogService;
    private final StoryService storyService;

    @Operation(summary = "The project backlog in rank order",
            description = "Pass sprintId=none to see only items not yet committed to a sprint.")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping
    public PageResponse<StoryResponse> list(@PathVariable UUID projectId,
                                            @RequestParam(required = false) UUID epicId,
                                            @RequestParam(required = false) StoryStatus status,
                                            @RequestParam(required = false) StoryType type,
                                            @RequestParam(required = false) Priority priority,
                                            @RequestParam(required = false) UUID assigneeId,
                                            @RequestParam(required = false) String sprintId,
                                            @Valid PageQuery query) {
        // "none" is the documented sentinel for "not in any sprint"; anything else is a
        // sprint id, and an absent parameter means "no sprint filter at all".
        boolean backlogOnly = "none".equalsIgnoreCase(sprintId);
        UUID sprintFilter = (sprintId == null || backlogOnly) ? null : UUID.fromString(sprintId);

        return storyService.search(projectId, epicId, status, type, priority, assigneeId, sprintFilter,
                backlogOnly, query);
    }

    @Operation(summary = "Reorder items relative to their new neighbours")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PostMapping("/rank")
    public List<RankedItemResponse> rank(@PathVariable UUID projectId,
                                         @Valid @RequestBody RankRequest request) {
        return backlogService.rank(projectId, request);
    }

    @Operation(summary = "Apply the same changes to many items",
            description = "Items that cannot take a change are reported in 'skipped' rather than "
                    + "failing the whole batch.")
    @PreAuthorize("@perm.has('backlog:manage')")
    @PostMapping("/bulk-update")
    public BulkUpdateResponse bulkUpdate(@PathVariable UUID projectId,
                                         @Valid @RequestBody BulkUpdateRequest request) {
        return backlogService.bulkUpdate(projectId, request);
    }
}
