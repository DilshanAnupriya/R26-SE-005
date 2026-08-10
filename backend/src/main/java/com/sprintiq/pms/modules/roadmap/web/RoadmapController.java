package com.sprintiq.pms.modules.roadmap.web;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.roadmap.application.RoadmapService;
import com.sprintiq.pms.modules.roadmap.domain.InitiativeStatus;
import com.sprintiq.pms.modules.roadmap.web.dto.RoadmapDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Roadmap", description = "Initiatives, the work behind them, and the lanes they are drawn in")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/roadmap")
@RequiredArgsConstructor
public class RoadmapController {

    private final RoadmapService roadmapService;

    @Operation(summary = "The roadmap: lanes by project, columns by quarter")
    @PreAuthorize("@perm.has('initiative:read')")
    @GetMapping
    public RoadmapResponse roadmap(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) InitiativeStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return roadmapService.roadmap(projectId, status, from, to);
    }

    @Operation(summary = "Get one initiative with the work linked to it")
    @PreAuthorize("@perm.has('initiative:read')")
    @GetMapping("/initiatives/{initiativeId}")
    public InitiativeResponse get(@PathVariable UUID initiativeId) {
        return roadmapService.get(initiativeId);
    }

    @Operation(summary = "Create an initiative, optionally with the work it is made of")
    @PreAuthorize("@perm.has('initiative:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/initiatives")
    public InitiativeResponse create(@Valid @RequestBody CreateInitiativeRequest request) {
        return roadmapService.create(request);
    }

    @Operation(summary = "Update an initiative")
    @PreAuthorize("@perm.has('initiative:manage')")
    @PatchMapping("/initiatives/{initiativeId}")
    public InitiativeResponse update(@PathVariable UUID initiativeId,
                                     @Valid @RequestBody UpdateInitiativeRequest request) {
        return roadmapService.update(initiativeId, request);
    }

    @Operation(summary = "Delete an initiative")
    @PreAuthorize("@perm.has('initiative:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/initiatives/{initiativeId}")
    public void delete(@PathVariable UUID initiativeId) {
        roadmapService.delete(initiativeId);
    }

    @Operation(summary = "Add an epic or story to an initiative")
    @PreAuthorize("@perm.has('initiative:manage')")
    @PostMapping("/initiatives/{initiativeId}/items")
    public InitiativeResponse link(@PathVariable UUID initiativeId,
                                   @Valid @RequestBody LinkRequest request) {
        return roadmapService.linkItem(initiativeId, request);
    }

    @Operation(summary = "Remove an epic or story from an initiative")
    @PreAuthorize("@perm.has('initiative:manage')")
    @DeleteMapping("/initiatives/{initiativeId}/items/{entityType}/{entityId}")
    public InitiativeResponse unlink(@PathVariable UUID initiativeId,
                                     @PathVariable EntityType entityType,
                                     @PathVariable UUID entityId) {
        return roadmapService.unlinkItem(initiativeId, entityType, entityId);
    }
}
