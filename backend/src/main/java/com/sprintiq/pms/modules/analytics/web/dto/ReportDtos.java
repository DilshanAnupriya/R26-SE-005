package com.sprintiq.pms.modules.analytics.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Chart-shaped responses.
 *
 * <p>Every series carries its own labels and units rather than leaving the client to
 * infer them. A chart component that has to know that {@code cycleTime} is in days while
 * {@code effort} is in minutes is a chart component that will eventually plot one as the
 * other.</p>
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    @Schema(name = "VelocityPoint")
    public record VelocityPoint(UUID sprintId,
                                String sprintName,
                                int sequenceNo,
                                BigDecimal committedPoints,
                                BigDecimal completedPoints,
                                BigDecimal addedPoints,
                                BigDecimal removedPoints,
                                BigDecimal completionRate,
                                LocalDate endDate) {}

    @Schema(name = "VelocityReportResponse")
    public record VelocityReportResponse(UUID projectId,
                                         List<VelocityPoint> sprints,
                                         BigDecimal averageVelocity,
                                         /** Standard deviation — how predictable the team is. */
                                         BigDecimal velocityStdDev,
                                         BigDecimal averageCompletionRate,
                                         String unit) {}

    @Schema(name = "BurnPoint")
    public record BurnPoint(LocalDate date,
                            BigDecimal ideal,
                            BigDecimal actual,
                            BigDecimal completed,
                            BigDecimal scopeChange) {}

    @Schema(name = "BurnReportResponse")
    public record BurnReportResponse(UUID sprintId,
                                     LocalDate startDate,
                                     LocalDate endDate,
                                     BigDecimal committedPoints,
                                     BigDecimal completedPoints,
                                     List<BurnPoint> points,
                                     String unit) {}

    /** Cumulative flow: one stacked band per workflow state, sampled daily. */
    @Schema(name = "CfdBand")
    public record CfdBand(String state, List<Long> counts) {}

    @Schema(name = "CfdResponse")
    public record CfdResponse(UUID projectId,
                              List<LocalDate> dates,
                              List<CfdBand> bands) {}

    @Schema(name = "DurationDistributionResponse",
            description = "Cycle or lead time. Percentiles, not just a mean — the tail is the story.")
    public record DurationDistributionResponse(UUID projectId,
                                               String metric,
                                               long sampleSize,
                                               BigDecimal median,
                                               BigDecimal p85,
                                               BigDecimal p95,
                                               BigDecimal average,
                                               BigDecimal longest,
                                               List<DurationSample> samples,
                                               String unit) {}

    @Schema(name = "DurationSample")
    public record DurationSample(UUID itemId, String itemKey, String title, BigDecimal days,
                                 Instant completedAt) {}

    @Schema(name = "ProductivityRow")
    public record ProductivityRow(UUID userId,
                                  String fullName,
                                  String avatarUrl,
                                  long itemsCompleted,
                                  BigDecimal pointsCompleted,
                                  long minutesLogged,
                                  BigDecimal averageCycleTimeDays,
                                  long reopenedCount) {}

    @Schema(name = "ProductivityReportResponse")
    public record ProductivityReportResponse(UUID projectId,
                                             UUID sprintId,
                                             List<ProductivityRow> rows,
                                             /** Deliberately absent: a per-person ranking. */
                                             String note) {}

    @Schema(name = "CompletionRateResponse")
    public record CompletionRateResponse(UUID projectId,
                                         long totalItems,
                                         long completedItems,
                                         BigDecimal completionPct,
                                         BigDecimal pointsCompletionPct,
                                         List<VelocityPoint> bySprint) {}

    @Schema(name = "WorkloadRow")
    public record WorkloadRow(UUID userId,
                             String fullName,
                             String avatarUrl,
                             long openItems,
                             long blockedItems,
                             int estimatedMinutes,
                             BigDecimal loadPct,
                             boolean overAllocated) {}

    @Schema(name = "WorkloadBalanceResponse")
    public record WorkloadBalanceResponse(UUID projectId,
                                          List<WorkloadRow> rows,
                                          BigDecimal averageLoadPct,
                                          /** Spread between the busiest and the quietest. */
                                          BigDecimal imbalancePct,
                                          long unassignedItems) {}

    @Schema(name = "CostTrendRow")
    public record CostTrendRow(UUID sprintId,
                               String sprintName,
                               BigDecimal forecastAmount,
                               BigDecimal lowerBound,
                               BigDecimal upperBound,
                               BigDecimal budgetAmount,
                               BigDecimal variancePct,
                               Instant snapshotAt) {}

    @Schema(name = "CostTrendResponse")
    public record CostTrendResponse(UUID projectId,
                                    String currency,
                                    BigDecimal projectBudget,
                                    BigDecimal totalForecast,
                                    List<CostTrendRow> sprints) {}

    @Schema(name = "RiskSummaryRow")
    public record RiskSummaryRow(UUID sprintId,
                                 String sprintName,
                                 String riskType,
                                 BigDecimal riskScore,
                                 String severity,
                                 boolean acknowledged,
                                 Instant assessedAt) {}

    @Schema(name = "RiskSummaryResponse")
    public record RiskSummaryResponse(UUID projectId,
                                      List<RiskSummaryRow> rows,
                                      long atRiskCount,
                                      String highestSeverity) {}

    // ---------------------------------------------------------------------- export

    @Schema(name = "CreateExportRequest")
    public record CreateExportRequest(String report,
                                      UUID projectId,
                                      UUID sprintId,
                                      String format) {}

    @Schema(name = "ExportJobResponse")
    public record ExportJobResponse(UUID jobId,
                                    String report,
                                    String format,
                                    String status,
                                    String downloadUrl,
                                    String errorMessage,
                                    Instant requestedAt,
                                    Instant completedAt) {}
}
