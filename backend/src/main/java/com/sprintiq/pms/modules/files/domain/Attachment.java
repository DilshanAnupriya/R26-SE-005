package com.sprintiq.pms.modules.files.domain;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A file attached to a work item.
 *
 * <p>{@code storageKey} is generated, never derived from the uploaded name. A key built
 * from user input is a path-traversal bug waiting to be written, and two people
 * uploading {@code screenshot.png} must not collide. The original name is kept
 * separately, purely for display and download.</p>
 *
 * <p>{@code scanStatus} exists so that "not yet scanned" and "scanned, clean" are
 * different states. No scanner ships in this phase, and the honest representation of
 * that is {@code PENDING} on every row rather than a cheerful {@code CLEAN} that would
 * have to be un-lied later.</p>
 */
@Entity
@Table(name = "attachments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attachment extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20, updatable = false)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    /** As the uploader named it. Sanitised on the way in, never used as a path. */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "storage_key", nullable = false, length = 512, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Lets a re-upload of identical bytes be recognised, and detects corruption. */
    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 20)
    private ScanStatus scanStatus = ScanStatus.PENDING;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }
}
