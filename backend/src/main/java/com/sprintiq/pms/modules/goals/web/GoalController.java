package com.sprintiq.pms.modules.goals.web;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.goals.application.GoalService;
import com.sprintiq.pms.modules.goals.domain.GoalLevel;
import com.sprintiq.pms.modules.goals.domain.GoalStatus;
import com.sprintiq.pms.modules.goals.web.dto.GoalDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Goals", description = "Objectives, key results and check-ins")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    @Operation(summary = "Search goals")
    @PreAuthorize("@perm.has('goal:read')")
    @GetMapping
    public PageResponse<GoalResponse> list(@RequestParam(required = false) String period,
                                           @RequestParam(required = false) GoalStatus status,
                                           @RequestParam(required = false) GoalLevel level,
                                           @RequestParam(required = false) UUID projectId,
                                           @RequestParam(required = false) UUID ownerId,
                                           @Valid PageQuery query) {
        return goalService.search(period, status, level, projectId, ownerId, query);
    }

    @Operation(summary = "Header figures for a cycle")
    @PreAuthorize("@perm.has('goal:read')")
    @GetMapping("/summary")
    public GoalSummaryResponse summary(@RequestParam(required = false) String period) {
        return goalService.summary(period);
    }

    @Operation(summary = "Get one goal with its key results")
    @PreAuthorize("@perm.has('goal:read')")
    @GetMapping("/{goalId}")
    public GoalResponse get(@PathVariable UUID goalId) {
        return goalService.get(goalId);
    }

    @Operation(summary = "Create a goal, optionally with its key results")
    @PreAuthorize("@perm.has('goal:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public GoalResponse create(@Valid @RequestBody CreateGoalRequest request) {
        return goalService.create(request);
    }

    @Operation(summary = "Update a goal")
    @PreAuthorize("@perm.has('goal:manage')")
    @PatchMapping("/{goalId}")
    public GoalResponse update(@PathVariable UUID goalId,
                               @Valid @RequestBody UpdateGoalRequest request) {
        return goalService.update(goalId, request);
    }

    @Operation(summary = "Delete a goal; children ladder up to its parent")
    @PreAuthorize("@perm.has('goal:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{goalId}")
    public void delete(@PathVariable UUID goalId) {
        goalService.delete(goalId);
    }

    // ------------------------------------------------------------- key results

    @Operation(summary = "Add a key result to a goal")
    @PreAuthorize("@perm.has('goal:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{goalId}/key-results")
    public KeyResultResponse addKeyResult(@PathVariable UUID goalId,
                                          @Valid @RequestBody CreateKeyResultRequest request) {
        return goalService.addKeyResult(goalId, request);
    }

    @Operation(summary = "Update a key result")
    @PreAuthorize("@perm.has('goal:manage')")
    @PatchMapping("/key-results/{keyResultId}")
    public KeyResultResponse updateKeyResult(@PathVariable UUID keyResultId,
                                             @Valid @RequestBody UpdateKeyResultRequest request) {
        return goalService.updateKeyResult(keyResultId, request);
    }

    @Operation(summary = "Delete a key result")
    @PreAuthorize("@perm.has('goal:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/key-results/{keyResultId}")
    public void deleteKeyResult(@PathVariable UUID keyResultId) {
        goalService.deleteKeyResult(keyResultId);
    }

    // ---------------------------------------------------------------- check-ins

    @Operation(summary = "Record a measurement",
            description = "Writes the check-in, moves the key result and rolls the goal up in one "
                    + "transaction, so history and headline cannot disagree.")
    @PreAuthorize("@perm.has('goal:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/key-results/{keyResultId}/checkins")
    public CheckinResponse checkIn(@PathVariable UUID keyResultId,
                                   @Valid @RequestBody CheckinRequest request) {
        return goalService.checkIn(keyResultId, request);
    }

    @Operation(summary = "Check-in history for a key result")
    @PreAuthorize("@perm.has('goal:read')")
    @GetMapping("/key-results/{keyResultId}/checkins")
    public List<CheckinResponse> history(@PathVariable UUID keyResultId) {
        return goalService.history(keyResultId);
    }

    // -------------------------------------------------------------------- links

    @Operation(summary = "Link delivery work to a key result")
    @PreAuthorize("@perm.has('goal:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/key-results/{keyResultId}/links")
    public void linkItem(@PathVariable UUID keyResultId, @Valid @RequestBody LinkItemRequest request) {
        goalService.linkItem(keyResultId, request);
    }

    @Operation(summary = "Unlink delivery work from a key result")
    @PreAuthorize("@perm.has('goal:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/key-results/{keyResultId}/links/{entityType}/{entityId}")
    public void unlinkItem(@PathVariable UUID keyResultId,
                           @PathVariable EntityType entityType,
                           @PathVariable UUID entityId) {
        goalService.unlinkItem(keyResultId, entityType, entityId);
    }
}
