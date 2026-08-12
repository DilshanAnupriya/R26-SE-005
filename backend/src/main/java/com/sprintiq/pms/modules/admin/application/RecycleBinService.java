package com.sprintiq.pms.modules.admin.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.admin.web.dto.AdminDtos.RecycleBinEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The recovery window for soft-deleted items, assembled from whatever the modules offer.
 *
 * <p>This class knows nothing about projects, stories or tasks — it routes to a
 * {@link RecycleBinContributor}. Adding a recoverable type is one class in the module
 * that owns it, with no edit here.</p>
 */
@Service
@RequiredArgsConstructor
public class RecycleBinService {

    /** How long a deleted item stays recoverable before a purge job may remove it. */
    public static final Duration RETENTION = Duration.ofDays(30);

    private final List<RecycleBinContributor> contributors;

    /** The types that actually have a recycle bin, for the admin screen's tabs. */
    @Transactional(readOnly = true)
    public List<EntityType> supportedTypes() {
        return contributors.stream().map(RecycleBinContributor::entityType).sorted().toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<RecycleBinEntry> list(EntityType entityType, PageQuery query) {
        return contributorFor(entityType == null ? EntityType.PROJECT : entityType)
                .deletedItems(query);
    }

    @Transactional
    public void restore(EntityType entityType, UUID entityId) {
        contributorFor(entityType).restore(entityId);
    }

    private RecycleBinContributor contributorFor(EntityType entityType) {
        return contributors.stream()
                .filter(contributor -> contributor.entityType() == entityType)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException(
                        "%s items are not held in the recycle bin".formatted(entityType)));
    }
}
