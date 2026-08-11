package com.sprintiq.pms.modules.admin.web.dto;

import com.sprintiq.pms.common.domain.EntityType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * One recoverable item.
     *
     * <p>{@code purgeAfter} is shown rather than left implicit. "Deleted 12 days ago" is
     * only actionable next to the answer to "and how long have I got".</p>
     */
    @Schema(name = "RecycleBinEntry")
    public record RecycleBinEntry(EntityType entityType,
                                  UUID entityId,
                                  String itemKey,
                                  String title,
                                  UUID projectId,
                                  UUID deletedBy,
                                  String deletedByName,
                                  Instant deletedAt,
                                  Instant purgeAfter,
                                  boolean restorable,
                                  String blockedReason) {}
}
