package com.sprintiq.pms.modules.analytics.application;

import com.sprintiq.pms.modules.analytics.domain.ExportJob;
import com.sprintiq.pms.modules.analytics.domain.ExportJobRepository;
import com.sprintiq.pms.modules.analytics.domain.ExportStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The job's status transitions, each committed on its own.
 *
 * <p>A separate bean from {@link ExportWorker} because {@code REQUIRES_NEW} is applied by
 * a proxy, and a worker calling its own annotated method goes straight past it — the
 * annotation would read as a guarantee while doing nothing.</p>
 *
 * <p>Independence is the point. A failed export has to leave a row saying it failed; if
 * the status write rolled back with the generation that failed, the job would sit at
 * {@code QUEUED} forever, which a client cannot tell apart from "still working".</p>
 */
@Component
@RequiredArgsConstructor
public class ExportJobStore {

    private final ExportJobRepository jobs;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ExportJob> find(UUID jobId) {
        return jobs.findById(jobId);
    }

    /**
     * Claims a queued job, returning false if someone else already has it.
     *
     * <p>The optimistic check is deliberate rather than a row lock: two nodes racing the
     * same job would otherwise both generate it, and the loser would leave an orphaned
     * blob in storage that nothing ever deletes.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID jobId) {
        Optional<ExportJob> found = jobs.findById(jobId);
        if (found.isEmpty() || found.get().getStatus() != ExportStatus.QUEUED) {
            return false;
        }
        ExportJob job = found.get();
        job.markRunning();
        jobs.save(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, String storageKey, String fileName, long size, int rows) {
        jobs.findById(jobId).ifPresent(job -> {
            job.markCompleted(storageKey, fileName, size, rows);
            jobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String message) {
        jobs.findById(jobId).ifPresent(job -> {
            job.markFailed(message);
            jobs.save(job);
        });
    }
}
