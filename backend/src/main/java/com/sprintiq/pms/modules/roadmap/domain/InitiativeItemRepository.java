package com.sprintiq.pms.modules.roadmap.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InitiativeItemRepository extends JpaRepository<InitiativeItem, UUID> {

    List<InitiativeItem> findByInitiativeId(UUID initiativeId);

    /** Batch form, so a roadmap of thirty lanes resolves its links in one query. */
    List<InitiativeItem> findByInitiativeIdIn(Collection<UUID> initiativeIds);

    boolean existsByInitiativeIdAndEntityTypeAndEntityId(
            UUID initiativeId, EntityType entityType, UUID entityId);

    /**
     * Which of these initiatives have any work attached at all.
     *
     * <p>One grouped query for the nightly sweep, which must know the difference
     * between "behind on its work" and "has no work" without resolving every link
     * through the delivery modules.</p>
     */
    @Query("""
            SELECT i.initiativeId FROM InitiativeItem i
            WHERE i.initiativeId IN :initiativeIds
            GROUP BY i.initiativeId
            """)
    List<UUID> initiativeIdsWithItems(@Param("initiativeIds") Collection<UUID> initiativeIds);

    void deleteByInitiativeIdAndEntityTypeAndEntityId(
            UUID initiativeId, EntityType entityType, UUID entityId);
}
