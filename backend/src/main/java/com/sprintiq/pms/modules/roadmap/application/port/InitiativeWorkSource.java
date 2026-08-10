package com.sprintiq.pms.modules.roadmap.application.port;

import com.sprintiq.pms.common.domain.EntityType;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the delivery work behind a roadmap link.
 *
 * <p>Declared here and implemented in the delivery modules, so the dependency points
 * inward: backlog knows about the roadmap's port, the roadmap knows nothing about
 * epics. Either side can be removed without the other changing, and the architecture
 * tests enforce that this module never reaches for a backlog repository.</p>
 */
public interface InitiativeWorkSource {

    /** Which entity type this source can describe. */
    EntityType supports();

    /**
     * Describes a batch of items in one query.
     *
     * <p>Batched rather than one call per item because a roadmap is a page of thirty
     * initiatives with several links each, and the per-item shape of this interface is
     * exactly how a screen ends up making two hundred round trips.</p>
     *
     * <p>Ids with no surviving entity are omitted from the result; callers treat a
     * missing key as {@link LinkedWork#missing()}.</p>
     */
    Map<UUID, LinkedWork> describe(Collection<UUID> entityIds);
}
