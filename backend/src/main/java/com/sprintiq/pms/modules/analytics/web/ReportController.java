package com.sprintiq.pms.modules.analytics.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.ai.application.CostForecastService;
import com.sprintiq.pms.modules.ai.application.RiskService;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.CostTrendPoint;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAssessmentResponse;
import com.sprintiq.pms.modules.analytics.application.AnalyticsService;
import com.sprintiq.pms.modules.analytics.application.ExportService;
import com.sprintiq.pms.modules.analytics.web.dto.ReportDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Charts and tables.
 *
 * <p>The cost and risk reports read the AI module's stored outputs rather than invoking
 * the models. Opening a reports page must not bill for eight inference calls, and a
 * report of what was forecast is a different question from what the model would say
 * right now.</p>
 */
@Tag(name = "Reports", description = "Delivery analytics")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/reports")
@RequiredArgsConstructor
public class ReportController {

    private final AnalyticsService analytics;
    private final RiskService riskService;
    private final CostForecastService costService;
    private final ExportService exportService;

    @Operation(summary = "Velocity across recent sprints, with its standard deviation")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/velocity")
    public VelocityReportResponse velocity(@RequestParam UUID projectId,
                                           @RequestParam(required = false) Integer sprints) {
        return analytics.velocity(projectId, sprints);
    }

    @Operation(summary = "Sprint burndown from recorded daily snapshots")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/burndown")
    public BurnReportResponse burndown(@RequestParam UUID sprintId) {
        return analytics.burndown(sprintId);
    }

    @Operation(summary = "Sprint burn-up — scope and delivery as separate lines")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/burnup")
    public BurnReportResponse burnup(@RequestParam UUID sprintId) {
        return analytics.burnup(sprintId);
    }

    @Operation(summary = "Cumulative flow",
            description = "Three bands, reconstructed from item timestamps. Per-column history "
                    + "would require transition snapshots the schema does not keep.")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/cfd")
    public CfdResponse cumulativeFlow(@RequestParam UUID projectId,
                                      @RequestParam(required = false) Integer days) {
        return analytics.cumulativeFlow(projectId, days);
    }

    @Operation(summary = "Cycle time — started to done, with percentiles")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/cycle-time")
    public DurationDistributionResponse cycleTime(@RequestParam UUID projectId) {
        return analytics.cycleTime(projectId);
    }

    @Operation(summary = "Lead time — created to done, with percentiles")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/lead-time")
    public DurationDistributionResponse leadTime(@RequestParam UUID projectId) {
        return analytics.leadTime(projectId);
    }

    @Operation(summary = "Per-person delivery figures",
            description = "Unranked on purpose. Story points are a planning unit; a league table "
                    + "built on them teaches people to inflate estimates.")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/productivity")
    public ProductivityReportResponse productivity(@RequestParam UUID projectId,
                                                   @RequestParam(required = false) UUID sprintId) {
        return analytics.productivity(projectId, sprintId);
    }

    @Operation(summary = "Completion rate by item and by points")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/completion-rate")
    public CompletionRateResponse completionRate(@RequestParam UUID projectId) {
        return analytics.completionRate(projectId);
    }

    @Operation(summary = "Workload distribution across the team")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/workload-balance")
    public WorkloadBalanceResponse workloadBalance(@RequestParam UUID projectId) {
        return analytics.workloadBalance(projectId);
    }

    @Operation(summary = "Cost forecast trend across sprints",
            description = "Reads stored M4 forecasts. Bounds are always returned alongside the "
                    + "figure — a point estimate on its own reads as a commitment.")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/cost-trend")
    public CostTrendResponse costTrend(@RequestParam UUID projectId) {
        List<CostTrendPoint> points = costService.projectTrend(projectId, null);
        BigDecimal total = points.stream()
                .map(CostTrendPoint::forecastAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CostTrendRow> rows = points.stream()
                .map(point -> new CostTrendRow(point.sprintId(), point.sprintName(),
                        point.forecastAmount(), point.lowerBound(), point.upperBound(),
                        point.budgetAmount(), null, point.snapshotAt()))
                .toList();

        return new CostTrendResponse(projectId, "USD", null, total, rows);
    }

    @Operation(summary = "Latest risk assessment per sprint")
    @PreAuthorize("@perm.has('report:read')")
    @GetMapping("/risk-summary")
    public RiskSummaryResponse riskSummary(@RequestParam UUID projectId) {
        List<RiskAssessmentResponse> assessments = riskService.forProject(projectId, 40);

        List<RiskSummaryRow> rows = assessments.stream()
                .map(assessment -> new RiskSummaryRow(assessment.sprintId(), null,
                        assessment.riskType().name(), assessment.riskScore(),
                        assessment.severity().name(), assessment.acknowledgedAt() != null,
                        assessment.createdAt()))
                .toList();

        long atRisk = assessments.stream().filter(RiskAssessmentResponse::atRisk).count();
        String highest = assessments.stream()
                .map(RiskAssessmentResponse::severity)
                .max(Enum::compareTo)
                .map(Enum::name)
                .orElse("LOW");

        return new RiskSummaryResponse(projectId, rows, atRisk, highest);
    }

    // --------------------------------------------------------------------- export

    @Operation(summary = "Queue a CSV export",
            description = "Returns immediately with a job id. Poll GET /reports/export/{jobId} "
                    + "until it reports COMPLETED, then follow downloadUrl.")
    @PreAuthorize("@perm.has('report:export')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/export")
    public ExportJobResponse requestExport(@Valid @RequestBody CreateExportRequest request) {
        return exportService.request(request);
    }

    @Operation(summary = "Your recent exports")
    @PreAuthorize("@perm.has('report:export')")
    @GetMapping("/export")
    public PageResponse<ExportJobResponse> myExports(@Valid PageQuery query) {
        return exportService.mine(query);
    }

    @Operation(summary = "Export status")
    @PreAuthorize("@perm.has('report:export')")
    @GetMapping("/export/{jobId}")
    public ExportJobResponse exportStatus(@PathVariable UUID jobId) {
        return exportService.status(jobId);
    }

    /**
     * Streams the generated CSV.
     *
     * <p>Same headers as an attachment download — {@code nosniff} and a forced
     * disposition — because a CSV is a file the browser will happily try to interpret,
     * and the contents include user-authored strings.</p>
     */
    @Operation(summary = "Download a completed export")
    @PreAuthorize("@perm.has('report:export')")
    @GetMapping("/export/{jobId}/download")
    public ResponseEntity<InputStreamResource> downloadExport(@PathVariable UUID jobId) {
        ExportService.Download download = exportService.download(jobId);
        String encodedName = URLEncoder.encode(download.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(download.sizeBytes()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, no-store")
                .body(new InputStreamResource(download.content()));
    }
}
