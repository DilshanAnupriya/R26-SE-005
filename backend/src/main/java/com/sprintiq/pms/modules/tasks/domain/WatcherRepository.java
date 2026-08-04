package com.sprintiq.pms.modules.tasks.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatcherRepository extends JpaRepository<Watcher, UUID> {

    List<Watcher> findByEntityTypeAndEntityId(EntityType entityType, UUID entityId);

    Optional<Watcher> findByEntityTypeAndEntityIdAndUserId(EntityType entityType, UUID entityId, UUID userId);

    boolean existsByEntityTypeAndEntityIdAndUserId(EntityType entityType, UUID entityId, UUID userId);

    void deleteByEntityTypeAndEntityId(EntityType entityType, UUID entityId);
}
