package com.sprintiq.pms.modules.analytics.application;

import com.sprintiq.pms.modules.analytics.domain.ExportJob;
import com.sprintiq.pms.modules.files.application.FileStorage;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates an export off the request thread.
 *
 * <p>Asynchronous because a backlog export of a large project is seconds of work, and a
 * request that holds a connection open that long fails behind any proxy with a sensible
 * read timeout.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportWorker {

    private final ExportJobStore store;
    private final ReportCsvRenderer renderer;
    private final FileStorage storage;

    /**
     * Schedules the run for after the caller's transaction commits.
     *
     * <p>Dispatching immediately races the commit: the worker opens its own transaction,
     * looks for the job id it was handed, and does not find it. That failure is
     * intermittent and load-dependent, which makes it the worst possible kind.</p>
     */
    public void runAfterCommit(UUID jobId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            run(jobId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                run(jobId);
            }
        });
    }

    /**
     * Runs one export.
     *
     * <p>The executor's {@code TenantTaskDecorator} carries the requester's context
     * across, which is what lets the tenant filter narrow every query this generation
     * runs. Without it the worker would either see nothing or, far worse, everything.</p>
     */
    @Async("eventExecutor")
    public void run(UUID jobId) {
        if (!store.claim(jobId)) {
            log.debug("Export {} was already claimed or is no longer queued", jobId);
            return;
        }

        Optional<ExportJob> found = store.find(jobId);
        if (found.isEmpty()) {
            log.warn("Export job {} vanished after being claimed", jobId);
            return;
        }
        ExportJob job = found.get();

        try {
            CsvWriter csv = renderer.render(job);
            byte[] content = csv.toBytes();
            String fileName = renderer.fileNameFor(job);
            String storageKey = "%s/exports/%s.csv".formatted(
                    TenantContext.requireOrganizationId(), jobId);

            storage.store(storageKey, new ByteArrayInputStream(content), content.length, "text/csv");
            store.complete(jobId, storageKey, fileName, content.length, csv.dataRowCount());

            log.info("Export {} ({}) produced {} rows, {} bytes", jobId, job.getReport(),
                    csv.dataRowCount(), content.length);
        } catch (RuntimeException ex) {
            // Recorded, not rethrown. An @Async method's exception goes to an uncaught
            // handler nobody is watching; the row is what the user is actually polling.
            log.error("Export {} failed", jobId, ex);
            store.fail(jobId, ex.getMessage());
        }
    }
}
