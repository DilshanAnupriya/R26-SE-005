package com.sprintiq.pms.modules.files.web.dto;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.files.domain.ScanStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public final class AttachmentDtos {

    private AttachmentDtos() {
    }

    @Schema(name = "AttachmentResponse")
    public record AttachmentResponse(UUID id,
                                     EntityType entityType,
                                     UUID entityId,
                                     String fileName,
                                     String contentType,
                                     long sizeBytes,
                                     boolean image,
                                     /** PENDING until a scanner runs; the UI shows the state. */
                                     ScanStatus scanStatus,
                                     UUID uploadedBy,
                                     String uploadedByName,
                                     String downloadUrl,
                                     Instant createdAt) {}
}
