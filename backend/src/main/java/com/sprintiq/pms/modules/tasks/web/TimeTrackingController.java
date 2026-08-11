package com.sprintiq.pms.modules.tasks.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.tasks.application.TimeTrackingService;
import com.sprintiq.pms.modules.tasks.web.dto.TaskDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Time tracking", description = "Timesheet and the start/stop timer")
@RestController
@RequestMapping(ApiConstants.API_V1)
@RequiredArgsConstructor
public class TimeTrackingController {

    private final TimeTrackingService timeTrackingService;

    @Operation(summary = "Timesheet across a project")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/time-entries")
    public PageResponse<TimeEntryResponse> timesheet(
            @RequestParam UUID projectId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID sprintId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Valid PageQuery query) {
        return timeTrackingService.timesheet(projectId, userId, sprintId, from, to, query);
    }

    @Operation(summary = "The caller's running timer, if any",
            description = "Returns 204 when no timer is running.")
    @PreAuthorize("@perm.has('time:log')")
    @GetMapping("/timer")
    public ResponseEntity<TimeEntryResponse> runningTimer() {
        TimeEntryResponse running = timeTrackingService.runningTimer();
        return running == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(running);
    }

    @Operation(summary = "Start a timer",
            description = "Any timer already running for the caller is stopped and logged first.")
    @PreAuthorize("@perm.has('time:log')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/timer/start")
    public TimeEntryResponse start(@Valid @RequestBody StartTimerRequest request) {
        return timeTrackingService.startTimer(request);
    }

    @Operation(summary = "Stop the running timer and log the elapsed time")
    @PreAuthorize("@perm.has('time:log')")
    @PostMapping("/timer/stop")
    public TimeEntryResponse stop(@RequestBody(required = false) StopTimerRequest request) {
        return timeTrackingService.stopTimer(request == null ? new StopTimerRequest(null, null) : request);
    }

    @Operation(summary = "Delete a time entry",
            description = "Users may delete their own entries; time:manage is required for anyone else's.")
    @PreAuthorize("@perm.hasAny('time:log', 'time:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/time-entries/{timeEntryId}")
    public void delete(@PathVariable UUID timeEntryId) {
        timeTrackingService.delete(timeEntryId);
    }
}
