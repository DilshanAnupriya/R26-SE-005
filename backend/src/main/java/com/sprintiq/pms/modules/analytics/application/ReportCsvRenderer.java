package com.sprintiq.pms.modules.analytics.application;

import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.modules.analytics.domain.ExportJob;
import com.sprintiq.pms.modules.analytics.domain.ExportReport;
import com.sprintiq.pms.modules.analytics.web.dto.ReportDtos.*;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a report into CSV.
 *
 * <p>Separate from the worker so the rendering is a pure function of a job plus the
 * analytics service — testable without a thread pool, and with no opinion about how it
 * came to be called.</p>
 *
 * <p>Every sheet leads with the identifying columns a human needs to make sense of the
 * rest. An export whose first column is a UUID is one nobody can read without going
 * back to the application it came from.</p>
 */
@Component
@RequiredArgsConstructor
public class ReportCsvRenderer {

    private final AnalyticsService analytics;
    private final StoryService storyService;

    @Transactional(readOnly = true)
    public CsvWriter render(ExportJob job) {
        return switch (job.getReport()) {
            case VELOCITY -> velocity(job);
            case BURNDOWN -> burndown(job);
            case CYCLE_TIME -> distribution(analytics.cycleTime(job.getProjectId()));
            case LEAD_TIME -> distribution(analytics.leadTime(job.getProjectId()));
            case PRODUCTIVITY -> productivity(job);
            case WORKLOAD_BALANCE -> workload(job);
            case COMPLETION_RATE -> completionRate(job);
            case BACKLOG -> backlog(job);
        };
    }

    /** The file a user sees in their downloads folder. */
    public String fileNameFor(ExportJob job) {
        String date = java.time.LocalDate.now().toString();
        return "sprintiq-%s-%s.csv".formatted(job.getReport().getSlug(), date);
    }

    // ------------------------------------------------------------------ per report

    private CsvWriter velocity(ExportJob job) {
        VelocityReportResponse report = analytics.velocity(job.getProjectId(), 50);
        CsvWriter csv = new CsvWriter().header("Sprint", "Sequence", "Ends", "Committed",
                "Completed", "Added", "Removed", "Completion %");
        for (VelocityPoint point : report.sprints()) {
            csv.row(point.sprintName(), point.sequenceNo(), point.endDate(),
                    point.committedPoints(), point.completedPoints(), point.addedPoints(),
                    point.removedPoints(), point.completionRate());
        }
        return csv;
    }

    private CsvWriter burndown(ExportJob job) {
        BurnReportResponse report = analytics.burndown(job.getSprintId());
        CsvWriter csv = new CsvWriter().header("Date", "Ideal remaining", "Actual remaining",
                "Completed", "Scope change");
        for (BurnPoint point : report.points()) {
            csv.row(point.date(), point.ideal(), point.actual(), point.completed(),
                    point.scopeChange());
        }
        return csv;
    }

    /**
     * Cycle and lead time export the individual samples, not the percentiles.
     *
     * <p>The percentiles are on screen already. Someone exporting a distribution is
     * about to do their own analysis, and handing them five summary numbers gives them
     * nothing they could not read off the page.</p>
     */
    private CsvWriter distribution(DurationDistributionResponse report) {
        CsvWriter csv = new CsvWriter().header("Item", "Title", "Days", "Completed at");
        for (DurationSample sample : report.samples()) {
            csv.row(sample.itemKey(), sample.title(), sample.days(), sample.completedAt());
        }
        return csv;
    }

    private CsvWriter productivity(ExportJob job) {
        ProductivityReportResponse report = analytics.productivity(job.getProjectId(),
                job.getSprintId());
        CsvWriter csv = new CsvWriter().header("Person", "Items completed", "Points completed",
                "Minutes logged", "Average cycle time (days)", "Reopened");
        for (ProductivityRow row : report.rows()) {
            csv.row(row.fullName(), row.itemsCompleted(), row.pointsCompleted(),
                    row.minutesLogged(), row.averageCycleTimeDays(), row.reopenedCount());
        }
        return csv;
    }

    private CsvWriter workload(ExportJob job) {
        WorkloadBalanceResponse report = analytics.workloadBalance(job.getProjectId());
        CsvWriter csv = new CsvWriter().header("Person", "Open items", "Blocked", "Estimated minutes",
                "Load %", "Over-allocated");
        for (WorkloadRow row : report.rows()) {
            csv.row(row.fullName(), row.openItems(), row.blockedItems(), row.estimatedMinutes(),
                    row.loadPct(), row.overAllocated() ? "yes" : "no");
        }
        return csv;
    }

    private CsvWriter completionRate(ExportJob job) {
        CompletionRateResponse report = analytics.completionRate(job.getProjectId());
        CsvWriter csv = new CsvWriter().header("Sprint", "Sequence", "Committed", "Completed",
                "Completion %");
        for (VelocityPoint point : report.bySprint()) {
            csv.row(point.sprintName(), point.sequenceNo(), point.committedPoints(),
                    point.completedPoints(), point.completionRate());
        }
        return csv;
    }

    private CsvWriter backlog(ExportJob job) {
        PageQuery query = new PageQuery();
        query.setSize(PageQuery.MAX_PAGE_SIZE);

        CsvWriter csv = new CsvWriter().header("Key", "Title", "Type", "Status", "Priority",
                "Points", "Estimate source", "AI suggestion", "Assignee", "Blocked", "Created");

        for (StoryResponse story : storyService
                .search(job.getProjectId(), null, null, null, null, null, null, false, query)
                .content()) {
            csv.row(story.itemKey(), story.title(), story.type(), story.status(), story.priority(),
                    story.storyPoints(), story.estimateSource(),
                    story.aiEstimate() == null ? null : story.aiEstimate().points(),
                    story.assigneeName(), story.blocked() ? "yes" : "no", story.createdAt());
        }
        return csv;
    }
}
