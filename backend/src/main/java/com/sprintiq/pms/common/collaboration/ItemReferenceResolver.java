package com.sprintiq.pms.common.collaboration;

import com.sprintiq.pms.common.domain.EntityType;

import java.util.List;
import java.util.UUID;

/**
 * Turns an {@code (entityType, entityId)} pair into the few facts every collaboration
 * feature needs about the thing being collaborated on.
 *
 * <p>Comments, attachments and the activity feed all attach to "some item" and all need
 * the same four things: which project it belongs to (for permissions and scoping), what
 * to call it in a notification, where to link, and who cares about it. Each of them
 * discovering that separately would mean three modules each importing the backlog, the
 * tasks module and the projects module.</p>
 *
 * <p>It lives in {@code common} for the same reason {@link EntityType} does — the
 * polymorphic association tables are written by several modules, and a port declared
 * inside any one of them would have to be imported across a boundary the layering rules
 * exist to protect.</p>
 */
public interface ItemReferenceResolver {

    /**
     * @param itemKey    {@code MBA-142}, or null for items that do not carry one
     * @param title      what a notification calls it
     * @param path       client-relative link, e.g. {@code /projects/{id}/stories/{id}}
     * @param interested people to notify by default — assignee, reporter, owner
     */
    record ItemReference(EntityType entityType,
                         UUID entityId,
                         UUID projectId,
                         String itemKey,
                         String title,
                         String path,
                         List<UUID> interested) {}

    boolean supports(EntityType entityType);

    ItemReference resolve(UUID entityId);
}
