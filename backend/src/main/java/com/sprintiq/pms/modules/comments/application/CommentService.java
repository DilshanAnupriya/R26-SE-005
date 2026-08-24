package com.sprintiq.pms.modules.comments.application;

import com.sprintiq.pms.common.collaboration.ItemReferenceRegistry;
import com.sprintiq.pms.common.collaboration.ItemReferenceResolver.ItemReference;
import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.AccessDeniedAppException;
import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.comments.application.port.WorkItemThreadListener;
import com.sprintiq.pms.modules.comments.domain.Comment;
import com.sprintiq.pms.modules.comments.domain.CommentRepository;
import com.sprintiq.pms.modules.comments.web.dto.CommentDtos.*;
import com.sprintiq.pms.modules.notifications.application.NotificationService;
import com.sprintiq.pms.modules.notifications.application.RealtimePublisher;
import com.sprintiq.pms.modules.notifications.domain.NotificationType;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.security.PermissionService;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Comments and their notification fan-out.
 *
 * <p>Posting a comment notifies three overlapping audiences: people named in it, the
 * author of the comment being replied to, and anyone the item lists as interested. They
 * are merged and de-duplicated, and the author is always removed — a system that emails
 * you about your own comment is one people turn off entirely.</p>
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    /**
     * Whoever wants to know when a thread moves. A list rather than one collaborator, and
     * empty in tests: this module must keep working when nothing is listening.
     */
    private final List<WorkItemThreadListener> threadListeners;


    static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt");

    private final CommentRepository comments;
    private final ItemReferenceRegistry items;
    private final NotificationService notifications;
    private final RealtimePublisher realtime;
    private final UserService userService;
    private final PermissionService permissions;

    // -------------------------------------------------------------------- writing

    @Transactional
    public CommentResponse create(CreateCommentRequest request) {
        ItemReference item = items.resolve(request.entityType(), request.entityId());

        UUID requestedParentId = request.parentCommentId();
        UUID parentId = null;
        if (requestedParentId != null) {
            Comment parent = comments.findByIdAndDeletedAtIsNull(requestedParentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Comment", requestedParentId));
            if (!parent.getEntityId().equals(request.entityId())) {
                throw new BusinessRuleException("That comment is on a different item");
            }
            // Flatten instead of rejecting: a user replying to a reply means "add to this
            // thread", and refusing them would be pedantry about a structure they cannot see.
            parentId = parent.isReply() ? parent.getParentCommentId() : parent.getId();
        }

        List<UUID> mentioned = MentionParser.parse(request.body());

        Comment comment = Comment.builder()
                .projectId(item.projectId())
                .entityType(request.entityType())
                .entityId(request.entityId())
                .parentCommentId(parentId)
                .authorId(TenantContext.requireUserId())
                .body(request.body())
                .mentions(mentioned)
                .build();
        Comment saved = comments.save(comment);

        fanOut(saved, item, mentioned);
        realtime.projectEvent(item.projectId(), "comment.created",
                Map.of("entityType", request.entityType(), "entityId", request.entityId(),
                        "commentId", saved.getId()));
        // A reply may have just answered a question the sprint is being charged for.
        threadListeners.forEach(listener ->
                listener.threadChanged(request.entityType(), request.entityId()));

        return toResponse(saved, summaries(List.of(saved)), List.of());
    }

    /**
     * Edits a comment.
     *
     * <p>Only the author, whatever permissions someone holds. {@code comment:moderate}
     * grants deletion, not rewriting: putting words in a colleague's mouth is not a
     * moderation action, and a thread nobody can trust is worse than one with a bad
     * comment removed from it.</p>
     */
    @Transactional
    public CommentResponse update(UUID commentId, UpdateCommentRequest request) {
        Comment comment = require(commentId);
        if (!comment.getAuthorId().equals(TenantContext.requireUserId())) {
            throw AccessDeniedAppException.missingPermission("comment:edit-own");
        }

        List<UUID> before = comment.getMentions();
        comment.setBody(request.body());
        comment.setMentions(MentionParser.parse(request.body()));
        comment.setEditedAt(Instant.now());
        Comment saved = comments.save(comment);

        // Only newly added mentions are notified. Re-notifying everyone on every typo fix
        // is how a mention stops meaning anything.
        List<UUID> added = new ArrayList<>(saved.getMentions());
        added.removeAll(before);
        if (!added.isEmpty()) {
            ItemReference item = items.resolve(comment.getEntityType(), comment.getEntityId());
            notifyMentions(saved, item, added);
        }

        return toResponse(saved, summaries(List.of(saved)), List.of());
    }

    @Transactional
    public void delete(UUID commentId) {
        Comment comment = require(commentId);
        boolean isAuthor = comment.getAuthorId().equals(TenantContext.requireUserId());
        if (!isAuthor && !permissions.has("comment:moderate")) {
            throw AccessDeniedAppException.missingPermission("comment:moderate");
        }
        comment.setDeletedAt(Instant.now());
        comments.save(comment);

        // Replies are removed with their parent: orphaned answers to a question nobody
        // can see are worse than nothing.
        List<Comment> replies = comments.findByParentCommentIdAndDeletedAtIsNull(commentId);
        replies.forEach(reply -> reply.setDeletedAt(Instant.now()));
        comments.saveAll(replies);

        realtime.projectEvent(comment.getProjectId(), "comment.deleted",
                Map.of("commentId", commentId));
        threadListeners.forEach(listener ->
                listener.threadChanged(comment.getEntityType(), comment.getEntityId()));
    }

    // -------------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> list(EntityType entityType, UUID entityId, PageQuery query) {
        Page<Comment> roots = comments.findRoots(entityType, entityId,
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.ASC, "createdAt")));

        List<Comment> replies = roots.isEmpty()
                ? List.of()
                : comments.findRepliesTo(roots.getContent().stream().map(Comment::getId).toList());

        List<Comment> everything = new ArrayList<>(roots.getContent());
        everything.addAll(replies);
        Map<UUID, UserSummary> people = summaries(everything);

        Map<UUID, List<Comment>> byParent = new HashMap<>();
        replies.forEach(reply -> byParent
                .computeIfAbsent(reply.getParentCommentId(), key -> new ArrayList<>()).add(reply));

        return PageResponse.from(roots, root -> toResponse(root, people,
                byParent.getOrDefault(root.getId(), List.of()).stream()
                        .map(reply -> toResponse(reply, people, List.of()))
                        .toList()));
    }

    @Transactional(readOnly = true)
    public CommentCountResponse count(EntityType entityType, UUID entityId) {
        return new CommentCountResponse(entityType, entityId,
                comments.countByEntityTypeAndEntityIdAndDeletedAtIsNull(entityType, entityId));
    }

    // ------------------------------------------------------------------ internals

    private void fanOut(Comment comment, ItemReference item, List<UUID> mentioned) {
        notifyMentions(comment, item, mentioned);

        // A reply tells the person being replied to, unless they were already mentioned.
        if (comment.isReply()) {
            comments.findByIdAndDeletedAtIsNull(comment.getParentCommentId())
                    .map(Comment::getAuthorId)
                    .filter(authorId -> !mentioned.contains(authorId))
                    .ifPresent(authorId -> notifications.notify(new NotificationService.Notify(
                            authorId, NotificationType.COMMENT_REPLY,
                            "%s replied to your comment".formatted(actorName()),
                            excerpt(comment.getBody()), comment.getEntityType(),
                            comment.getEntityId(), item.path())));
            return;
        }

        List<UUID> others = new ArrayList<>(item.interested());
        others.removeAll(mentioned);
        notifications.notifyAll(others, NotificationType.COMMENT,
                "New comment on %s".formatted(item.itemKey() == null ? item.title() : item.itemKey()),
                excerpt(comment.getBody()), comment.getEntityType(), comment.getEntityId(),
                item.path());
    }

    private void notifyMentions(Comment comment, ItemReference item, List<UUID> mentioned) {
        notifications.notifyAll(mentioned, NotificationType.MENTION,
                "%s mentioned you on %s".formatted(actorName(),
                        item.itemKey() == null ? item.title() : item.itemKey()),
                excerpt(comment.getBody()), comment.getEntityType(), comment.getEntityId(),
                item.path());
    }

    private Comment require(UUID commentId) {
        return comments.findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
    }

    private Map<UUID, UserSummary> summaries(List<Comment> batch) {
        Set<UUID> userIds = new HashSet<>();
        for (Comment comment : batch) {
            userIds.add(comment.getAuthorId());
            userIds.addAll(comment.getMentions());
        }
        return userService.summariesFor(userIds);
    }

    private CommentResponse toResponse(Comment comment, Map<UUID, UserSummary> people,
                                       List<CommentResponse> replies) {
        UUID currentUser = TenantContext.userId().orElse(null);
        return new CommentResponse(comment.getId(), comment.getEntityType(), comment.getEntityId(),
                comment.getParentCommentId(), author(people, comment.getAuthorId()),
                comment.getBody(), comment.getBodyHtml(),
                comment.getMentions().stream().map(id -> author(people, id)).toList(),
                comment.getEditedAt() != null, comment.getEditedAt(), replies,
                comment.getAuthorId().equals(currentUser), comment.getCreatedAt());
    }

    private CommentAuthorResponse author(Map<UUID, UserSummary> people, UUID userId) {
        UserSummary summary = people.get(userId);
        return summary == null
                ? new CommentAuthorResponse(userId, "Unknown user", null)
                : new CommentAuthorResponse(userId, summary.fullName(), summary.avatarUrl());
    }

    private String actorName() {
        return TenantContext.userId()
                .map(userId -> userService.summariesFor(List.of(userId)).get(userId))
                .map(UserSummary::fullName)
                .orElse("Someone");
    }

    /** Notification bodies show a preview, with mention markup rendered as plain text. */
    private String excerpt(String body) {
        String plain = MentionParser.toPlainText(body);
        if (plain == null) return null;
        String collapsed = plain.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 199) + "…";
    }
}
