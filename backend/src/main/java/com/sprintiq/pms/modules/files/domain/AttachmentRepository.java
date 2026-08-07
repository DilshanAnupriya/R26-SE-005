package com.sprintiq.pms.modules.files.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    Optional<Attachment> findByIdAndDeletedAtIsNull(UUID id);

    List<Attachment> findByEntityTypeAndEntityIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            EntityType entityType, UUID entityId);

    long countByEntityTypeAndEntityIdAndDeletedAtIsNull(EntityType entityType, UUID entityId);
}
