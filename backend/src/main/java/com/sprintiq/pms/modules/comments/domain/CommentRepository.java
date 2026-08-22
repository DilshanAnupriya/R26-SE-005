package com.sprintiq.pms.modules.comments.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * Timing and authorship of every comment on a set of items in a time window, oldest
     * first — what the collaboration risk model measures reply latency from.
     *
     * <p>Projected rather than hydrated: a sprint's worth of tasks can carry hundreds of
     * comments, all of them long, and the caller needs three columns. Replies are
     * included, because a threaded reply is the clearest evidence of somebody answering
     * somebody.</p>
     */
    @Query("""
            SELECT c.entityId AS itemId, c.authorId AS authorId, c.createdAt AS postedAt
            FROM Comment c
            WHERE c.entityType = :entityType AND c.entityId IN :itemIds
              AND c.deletedAt IS NULL
              AND c.createdAt >= :from AND c.createdAt <= :to
            ORDER BY c.entityId ASC, c.createdAt ASC
            """)
    List<CommentTiming> findTimingsForEntities(@Param("entityType") EntityType entityType,
                                               @Param("itemIds") Collection<UUID> itemIds,
                                               @Param("from") Instant from,
                                               @Param("to") Instant to);

    /** Projection for {@link #findTimingsForEntities}. */
    interface CommentTiming {
        UUID getItemId();

        UUID getAuthorId();

        Instant getPostedAt();
    }

    List<Comment> findByParentCommentIdAndDeletedAtIsNull(UUID parentCommentId);
}
