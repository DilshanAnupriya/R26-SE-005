package com.sprintiq.pms.modules.comments.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.comments.domain.Comment;
import com.sprintiq.pms.modules.comments.domain.CommentRepository;
import com.sprintiq.pms.modules.tasks.application.CommentThreadReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Answers the tasks module's question about comment timing, without handing over
 * comment rows.
 *
 * <p>Separate from {@link CommentService} on purpose. That class is the module's
 * write-and-read API for people using the product — it notifies, it publishes realtime
 * events, it checks permissions. This is a read for a model, and the two having
 * different reasons to change is exactly why they are different classes.</p>
 */
@Component
@RequiredArgsConstructor
public class CommentThreadProvider implements CommentThreadReader {

    private final CommentRepository comments;

    @Override
    @Transactional(readOnly = true)
    public List<ThreadEntry> threadEntries(EntityType entityType,
                                           Collection<UUID> itemIds,
                                           Instant from,
                                           Instant to) {
        // An empty IN-list is a syntax error on some dialects and a full table scan on
        // others. A sprint with no tasks is ordinary, not exceptional.
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        return comments.findTimingsForEntities(entityType, itemIds, from, to).stream()
                .map(row -> new ThreadEntry(row.getItemId(), row.getAuthorId(), row.getPostedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ThreadStats> threadStats(EntityType entityType, Collection<UUID> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        // Every comment on every item in one query, then grouped here. A per-item query
        // would be one round trip per card on the board.
        List<Comment> all = comments.findAllForEntities(entityType, itemIds);
        Map<UUID, List<Comment>> byItem = all.stream()
                .collect(Collectors.groupingBy(Comment::getEntityId));

        List<ThreadStats> stats = new ArrayList<>(byItem.size());
        for (Map.Entry<UUID, List<Comment>> entry : byItem.entrySet()) {
            stats.add(statsFor(entry.getKey(), entry.getValue()));
        }
        return stats;
    }

    private static ThreadStats statsFor(UUID itemId, List<Comment> thread) {
        Map<UUID, List<Comment>> repliesByParent = thread.stream()
                .filter(Comment::isReply)
                .collect(Collectors.groupingBy(Comment::getParentCommentId));

        long replies = thread.stream().filter(Comment::isReply).count();
        Instant oldestUnanswered = null;

        for (Comment root : thread) {
            if (root.isReply()) {
                continue;
            }
            boolean answered = repliesByParent.getOrDefault(root.getId(), List.of()).stream()
                    // Answered by somebody else. A second comment from the person who
                    // asked is them still waiting, not the thread being picked up.
                    .anyMatch(reply -> !Objects.equals(reply.getAuthorId(), root.getAuthorId()));
            if (answered) {
                continue;
            }
            if (oldestUnanswered == null || root.getCreatedAt().isBefore(oldestUnanswered)) {
                oldestUnanswered = root.getCreatedAt();
            }
        }
        return new ThreadStats(itemId, thread.size() - replies, replies, oldestUnanswered);
    }
}
