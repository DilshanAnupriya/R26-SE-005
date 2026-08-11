package com.sprintiq.pms.modules.analytics.application;

import com.sprintiq.pms.common.error.AccessDeniedAppException;
import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.analytics.domain.*;
import com.sprintiq.pms.modules.analytics.web.dto.ReportDtos.CreateExportRequest;
import com.sprintiq.pms.modules.analytics.web.dto.ReportDtos.ExportJobResponse;
import com.sprintiq.pms.modules.files.application.FileStorage;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

/**
 * Queues, tracks and serves report exports.
 *
 * <p>Asynchronous because a backlog export of a large project is seconds of work, and an
 * HTTP request that holds a connection open for that long fails behind any proxy with a
 * sensible read timeout. The job is written first, then handed to a worker, so the
 * caller gets an id it can poll immediately.</p>
 *
 * <p>The write and the dispatch are deliberately separate methods on separate beans: the
 * worker must not start until the job row is committed, or it will look for a row the
 * database has not made visible yet.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ExportJobRepository jobs;
    private final ExportWorker worker;
    private final FileStorage storage;

    @Transactional
    public ExportJobResponse request(CreateExportRequest request) {
        ExportReport report = parseReport(request.report());
        validateScope(report, request);

        ExportJob job = ExportJob.builder()
                .requestedBy(TenantContext.requireUserId())
                .report(report)
                .format("CSV")
                .projectId(request.projectId())
                .sprintId(request.sprintId())
                .status(ExportStatus.QUEUED)
                .build();
        job.setOrganizationId(TenantContext.requireOrganizationId());
        ExportJob saved = jobs.save(job);

        // Dispatched after this transaction commits, so the worker's own transaction can
        // see the row. Calling it inline would race the commit and fail on a fresh id.
        worker.runAfterCommit(saved.getId());

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ExportJobResponse status(UUID jobId) {
        return toResponse(requireOwnJob(jobId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ExportJobResponse> mine(PageQuery query) {
        return PageResponse.from(
                jobs.findByRequestedByOrderByCreatedAtDesc(TenantContext.requireUserId(),
                        PageRequest.of(query.getPage(), query.getSize())),
                this::toResponse);
    }

    /** The generated file, once the job has finished. */
    @Transactional(readOnly = true)
    public Download download(UUID jobId) {
        ExportJob job = requireOwnJob(jobId);

        if (job.getStatus() == ExportStatus.FAILED) {
            throw new BusinessRuleException(
                    "That export failed: %s".formatted(job.getErrorMessage()));
        }
        if (job.getStatus() == ExportStatus.EXPIRED || job.isExpired()) {
            throw new BusinessRuleException(
                    "That export has expired. Exports are kept for 24 hours — run it again.");
        }
        if (!job.getStatus().isDownloadable() || job.getStorageKey() == null) {
            throw new BusinessRuleException("That export is still being generated.");
        }

        return new Download(job.getFileName(), job.getSizeBytes() == null ? 0 : job.getSizeBytes(),
                storage.read(job.getStorageKey()));
    }

    public record Download(String fileName, long sizeBytes, InputStream content) {}

    // ------------------------------------------------------------------ internals

    /**
     * An export belongs to the person who asked for it.
     *
     * <p>The tenant filter narrows to the organization, which would still let one
     * colleague download another's export — and an export is a bulk extract of exactly
     * the data a permission boundary exists to control.</p>
     */
    private ExportJob requireOwnJob(UUID jobId) {
        ExportJob job = jobs.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Export job", jobId));
        if (!job.getRequestedBy().equals(TenantContext.requireUserId())) {
            throw AccessDeniedAppException.missingPermission("export:read-own");
        }
        return job;
    }

    private ExportReport parseReport(String report) {
        if (report == null || report.isBlank()) {
            throw new BusinessRuleException("Say which report to export");
        }
        for (ExportReport candidate : ExportReport.values()) {
            if (candidate.getSlug().equalsIgnoreCase(report) || candidate.name().equalsIgnoreCase(report)) {
                return candidate;
            }
        }
        throw new BusinessRuleException(
                "Unknown report '%s'. Supported: %s".formatted(report,
                        java.util.Arrays.stream(ExportReport.values())
                                .map(ExportReport::getSlug).collect(java.util.stream.Collectors.joining(", "))));
    }

    /** Rejected here rather than inside a worker thread ten seconds later. */
    private void validateScope(ExportReport report, CreateExportRequest request) {
        if (report.needsProject() && request.projectId() == null) {
            throw new BusinessRuleException("%s needs a projectId".formatted(report.getSlug()));
        }
        if (report.needsSprint() && request.sprintId() == null) {
            throw new BusinessRuleException("%s needs a sprintId".formatted(report.getSlug()));
        }
        if (request.format() != null && !"CSV".equalsIgnoreCase(request.format())) {
            throw new ApplicationException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Only CSV export is available");
        }
    }

    private ExportJobResponse toResponse(ExportJob job) {
        boolean expired = job.getStatus() == ExportStatus.EXPIRED || job.isExpired();
        return new ExportJobResponse(job.getId(), job.getReport().getSlug(), job.getFormat(),
                expired ? ExportStatus.EXPIRED.name() : job.getStatus().name(),
                job.getStatus().isDownloadable() && !expired
                        ? "/api/v1/reports/export/%s/download".formatted(job.getId())
                        : null,
                job.getErrorMessage(), job.getCreatedAt(), job.getCompletedAt());
    }

    /** Marks stale jobs so a crashed worker does not leave a permanent spinner. */
    @Transactional
    public void expireStale(Instant olderThan) {
        jobs.findExpired(olderThan, PageRequest.of(0, 200)).forEach(job -> {
            if (job.getStorageKey() != null) {
                storage.delete(job.getStorageKey());
            }
            job.setStatus(ExportStatus.EXPIRED);
            job.setStorageKey(null);
            jobs.save(job);
        });
    }
}
