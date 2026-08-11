package com.sprintiq.pms.modules.projects.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ItemLabelRepository extends JpaRepository<ItemLabel, UUID> {

    List<ItemLabel> findByEntityTypeAndEntityId(EntityType entityType, UUID entityId);

    /** Batch variant so a board or backlog page resolves labels in one query, not N. */
    List<ItemLabel> findByEntityTypeAndEntityIdIn(EntityType entityType, Collection<UUID> entityIds);

    boolean existsByLabelIdAndEntityTypeAndEntityId(UUID labelId, EntityType entityType, UUID entityId);

    void deleteByLabelIdAndEntityTypeAndEntityId(UUID labelId, EntityType entityType, UUID entityId);

    void deleteByEntityTypeAndEntityId(EntityType entityType, UUID entityId);

    @Query("SELECT il.entityId FROM ItemLabel il WHERE il.entityType = :entityType AND il.labelId = :labelId")
    List<UUID> findEntityIdsByLabel(@Param("entityType") EntityType entityType, @Param("labelId") UUID labelId);
}
