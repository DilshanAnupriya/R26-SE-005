package com.sprintiq.pms.modules.analytics.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.analytics.application.TimelineService;
import com.sprintiq.pms.modules.analytics.web.dto.TimelineDtos.TimelineResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Timeline", description = "The delivery timeline, assembled from epics, sprints and milestones")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/timeline")
@RequiredArgsConstructor
public class TimelineController {

    private final TimelineService timelineService;

    @Operation(summary = "Timeline for a project")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping
    public TimelineResponse timeline(
            @RequestParam @NotNull UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return timelineService.timeline(projectId, from, to);
    }
}
