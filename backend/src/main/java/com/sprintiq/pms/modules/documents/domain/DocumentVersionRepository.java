package com.sprintiq.pms.modules.documents.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    /** Newest first — the question is almost always "what changed recently". */
    List<DocumentVersion> findByDocumentIdOrderByVersionNoDesc(UUID documentId);

    Optional<DocumentVersion> findByDocumentIdAndVersionNo(UUID documentId, int versionNo);
}
