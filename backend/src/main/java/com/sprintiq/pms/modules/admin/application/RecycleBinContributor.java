package com.sprintiq.pms.modules.admin.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.admin.web.dto.AdminDtos.RecycleBinEntry;

import java.util.UUID;

/**
 * A module's contribution to the recycle bin.
 *
 * <p>Each module lists and restores its own deleted rows. Restoration especially cannot
 * be generic: putting a story back needs a valid rank, a sprint that may since have
 * closed, and a workflow state that may since have been removed. A central service
 * writing {@code deleted_at = null} would restore rows into states the application
 * cannot render, and would have to learn every module's invariants to avoid it.</p>
 */
public interface RecycleBinContributor {

    EntityType entityType();

    PageResponse<RecycleBinEntry> deletedItems(PageQuery query);

    void restore(UUID entityId);
}
