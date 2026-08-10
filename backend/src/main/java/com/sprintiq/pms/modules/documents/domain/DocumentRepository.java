package com.sprintiq.pms.modules.documents.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndDeletedAtIsNull(UUID id);

    List<Document> findByParentIdAndDeletedAtIsNull(UUID parentId);

    /**
     * The whole tree in one query, ordered the way it is drawn.
     *
     * <p>Unpaged and assembled in memory rather than fetched level by level. A tree
     * walked one level per request is N round trips for N folders, and the reader is
     * waiting for all of them before anything renders.</p>
     */
    @Query("""
            SELECT d FROM Document d
            WHERE d.deletedAt IS NULL
              AND (:projectId IS NULL OR d.projectId = :projectId)
            ORDER BY d.position ASC, d.title ASC
            """)
    List<Document> findTree(@Param("projectId") UUID projectId);

    @Query("""
            SELECT d FROM Document d
            WHERE d.deletedAt IS NULL AND d.kind = 'DOCUMENT'
              AND LOWER(d.title) LIKE :term
            ORDER BY d.updatedAt DESC
            """)
    List<Document> search(@Param("term") String term);

    @Query("""
            SELECT MAX(d.position) FROM Document d
            WHERE d.deletedAt IS NULL
              AND (:parentId IS NULL AND d.parentId IS NULL OR d.parentId = :parentId)
            """)
    Optional<Short> findMaxPosition(@Param("parentId") UUID parentId);
}
