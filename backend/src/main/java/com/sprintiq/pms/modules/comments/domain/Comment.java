package com.sprintiq.pms.modules.comments.domain;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A comment on any work item, optionally replying to another comment.
 *
 * <p>One level of nesting, not a tree. Threads on a story are conversations about one
 * thing; arbitrary depth turns them into an outline nobody reads to the bottom of, and
 * it makes "show me the last three replies" a recursive query for no product gain.</p>
 *
 * <p>{@code mentions} is denormalised alongside the body. The body is the source of
 * truth for what was written, but resolving @names out of it on every read means a
 * notification's audience depends on parsing rules that may have changed since it was
 * posted. The ids are captured once, at write time, from the text as it was then.</p>
 *
 * <p>Soft-deleted rather than removed: a deleted comment leaves replies that no longer
 * make sense on their own, and audit needs the row.</p>
 */
@Entity
@Table(name = "comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comment extends TenantAwareEntity implements SoftDeletable {

    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20, updatable = false)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "parent_comment_id", updatable = false)
    private UUID parentCommentId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /** Rendered, sanitised HTML. Null until the renderer has run. */
    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "mentions", nullable = false, columnDefinition = "jsonb")
    private List<UUID> mentions = new ArrayList<>();

    /** Set on every edit after the first save, so the UI can show "edited". */
    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isReply() {
        return parentCommentId != null;
    }
}
