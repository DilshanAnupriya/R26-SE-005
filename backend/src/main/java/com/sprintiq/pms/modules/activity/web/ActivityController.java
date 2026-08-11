package com.sprintiq.pms.modules.activity.web;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.activity.application.ActivityFeedService;
import com.sprintiq.pms.modules.activity.web.dto.ActivityDtos.ActivityResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Product history — what changed, when and by whom.
 *
 * <p>Distinct from the audit log, which is a governance trail behind
 * {@code audit:read}. This one is read by everyone who can see the project, because
 * "who moved this to In Review" is a normal question during a stand-up.</p>
 */
@Tag(name = "Activity", description = "Item history and the project feed")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityFeedService feedService;

    @Operation(summary = "Activity feed")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping
    public PageResponse<ActivityResponse> feed(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) EntityType entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Valid PageQuery query) {
        return feedService.feed(projectId, entityType, entityId, actorId, from, to, query);
    }

    @Operation(summary = "One item's full history")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/{entityType}/{entityId}")
    public PageResponse<ActivityResponse> history(@PathVariable EntityType entityType,
                                                  @PathVariable UUID entityId,
                                                  @Valid PageQuery query) {
        return feedService.history(entityType, entityId, query);
    }
}
