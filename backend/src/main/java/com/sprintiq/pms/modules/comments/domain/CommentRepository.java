package com.sprintiq.pms.modules.comments.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    Optional<Comment> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Top-level comments on an item, newest last so the thread reads like a conversation.
     * Replies are fetched separately by {@link #findRepliesTo}.
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.entityType = :entityType AND c.entityId = :entityId
              AND c.parentCommentId IS NULL AND c.deletedAt IS NULL
            """)
    Page<Comment> findRoots(@Param("entityType") EntityType entityType,
                            @Param("entityId") UUID entityId,
                            Pageable pageable);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentCommentId IN :parentIds AND c.deletedAt IS NULL
            ORDER BY c.createdAt ASC
            """)
    List<Comment> findRepliesTo(@Param("parentIds") List<UUID> parentIds);

    long countByEntityTypeAndEntityIdAndDeletedAtIsNull(EntityType entityType, UUID entityId);

    List<Comment> findByParentCommentIdAndDeletedAtIsNull(UUID parentCommentId);
}
