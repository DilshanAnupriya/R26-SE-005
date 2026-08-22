package com.sprintiq.pms.modules.comments.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.comments.domain.CommentRepository;
import com.sprintiq.pms.modules.tasks.application.CommentThreadReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

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
}
