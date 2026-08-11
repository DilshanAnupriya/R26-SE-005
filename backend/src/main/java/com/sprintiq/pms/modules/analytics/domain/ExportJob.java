package com.sprintiq.pms.modules.analytics.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One requested export, and what became of it.
 *
 * <p>Persisted rather than held in memory. A job in a heap does not survive a restart —
 * the user's spinner never resolves — and on more than one node the request that polls
 * for status is unlikely to reach the node doing the work.</p>
 *
 * <p>Extends the append-only root because the row is a record of a request. The status
 * transitions and the result fields are the narrow, one-way mutations a job needs; there
 * is no path that rewrites what was asked for.</p>
 */
@Entity
@Table(name = "export_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExportJob extends AppendOnlyTenantEntity {

    /** How long a finished export stays downloadable before the purge job removes it. */
    public static final Duration RETENTION = Duration.ofHours(24);

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "report", nullable = false, length = 48, updatable = false)
    private ExportReport report;

    @Builder.Default
    @Column(name = "format", nullable = false, length = 12, updatable = false)
    private String format = "CSV";

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "sprint_id", updatable = false)
    private UUID sprintId;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "parameters", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> parameters = new HashMap<>();

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ExportStatus status = ExportStatus.QUEUED;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Rows written. Zero is a legitimate, and useful, answer. */
    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public void markRunning() {
        status = ExportStatus.RUNNING;
        startedAt = Instant.now();
    }

    public void markCompleted(String storageKey, String fileName, long sizeBytes, int rowCount) {
        this.status = ExportStatus.COMPLETED;
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.rowCount = rowCount;
        this.completedAt = Instant.now();
        this.expiresAt = this.completedAt.plus(RETENTION);
    }

    public void markFailed(String message) {
        this.status = ExportStatus.FAILED;
        // Truncated rather than dropped: a user needs something to quote in a support
        // request, and the full stack trace is already in the logs under this job's id.
        this.errorMessage = message == null ? "Export failed"
                : message.length() <= 500 ? message : message.substring(0, 499) + "…";
        this.completedAt = Instant.now();
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
