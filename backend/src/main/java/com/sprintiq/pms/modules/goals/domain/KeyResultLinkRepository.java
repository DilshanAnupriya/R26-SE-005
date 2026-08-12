package com.sprintiq.pms.modules.goals.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface KeyResultLinkRepository extends JpaRepository<KeyResultLink, UUID> {

    List<KeyResultLink> findByKeyResultId(UUID keyResultId);

    List<KeyResultLink> findByKeyResultIdIn(Collection<UUID> keyResultIds);

    boolean existsByKeyResultIdAndEntityTypeAndEntityId(UUID keyResultId, EntityType entityType, UUID entityId);

    void deleteByKeyResultIdAndEntityTypeAndEntityId(UUID keyResultId, EntityType entityType, UUID entityId);
}
