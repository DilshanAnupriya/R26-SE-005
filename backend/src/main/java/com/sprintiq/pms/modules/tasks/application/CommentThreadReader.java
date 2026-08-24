package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.domain.EntityType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * When people wrote on these items, and who.
 *
 * <p>Comments belong to the comments module, but how much a sprint's tasks are being
 * discussed — and how long a question waits for an answer — is the larger half of what
 * the collaboration risk model reads. Same inversion as {@link StoryTaskCounter}: the
 * module that needs the numbers declares the question, the module that owns the rows
 * answers it, and no repository crosses a boundary.</p>
 *
 * <p>Only the timing and authorship come across, never the body. Nothing downstream of
 * this needs to know what anyone said, and a port that handed over comment text would
 * invite a future model that did.</p>
 */
public interface CommentThreadReader {

    /**
     * One comment, reduced to what a coordination measure can use.
     *
     * @param itemId   the task or story the comment is on, so threads stay separable
     * @param authorId who wrote it — a reply is only a reply if somebody else wrote it
     */
    record ThreadEntry(UUID itemId, UUID authorId, Instant postedAt) {}

    /**
     * Every comment on these items inside the window, oldest first.
     *
     * <p>Ordering is part of the contract rather than something the caller re-sorts:
     * the gap between consecutive comments is the measurement, and a caller that
     * assumed order it did not get would compute negative reply times and average them
     * into something that looked plausible.</p>
     *
     * <p>Replies are included alongside top-level comments. A threaded reply is the
     * clearest case of somebody answering somebody, and excluding it would make the
     * most responsive teams look like the least.</p>
     */
    List<ThreadEntry> threadEntries(EntityType entityType,
                                    Collection<UUID> itemIds,
                                    Instant from,
                                    Instant to);

    /**
     * How much conversation each item carries, and how long its oldest question has been
     * waiting.
     *
     * <p>Distinct from {@link #threadEntries} rather than derived from it. That one is
     * windowed to a sprint and flattens replies in with their parents, which is right for
     * a latency average and wrong for a badge on a card: a card has to show its whole
     * history, and it has to tell a comment from a reply.</p>
     *
     * @param oldestUnansweredAt when the longest-waiting unanswered comment was posted,
     *                           or null when every comment has an answer. A comment is
     *                           answered by a reply from somebody else — the author
     *                           adding a second thought is not the thread being picked
     *                           up, and counting it as one would make the least
     *                           responsive threads look like the most
     */
    record ThreadStats(UUID itemId, long commentCount, long replyCount, Instant oldestUnansweredAt) {

        public static ThreadStats none(UUID itemId) {
            return new ThreadStats(itemId, 0, 0, null);
        }
    }

    /** Stats for each of these items. Items with no comments may be omitted. */
    List<ThreadStats> threadStats(EntityType entityType, Collection<UUID> itemIds);
}
