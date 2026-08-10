package com.sprintiq.pms.modules.analytics.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.analytics.application.CalendarService;
import com.sprintiq.pms.modules.analytics.web.dto.CalendarDtos.CalendarResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Calendar", description = "Everything already dated, on a month grid")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;

    @Operation(summary = "A month of commitments across every project, or one")
    @PreAuthorize("@perm.has('backlog:read')")
    @GetMapping
    public CalendarResponse month(
            @Parameter(description = "Any date in the month; defaults to today")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month,
            @RequestParam(required = false) UUID projectId) {
        return calendarService.month(month, projectId);
    }
}
