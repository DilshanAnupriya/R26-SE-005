package com.sprintiq.pms.modules.comments.application.port;

import com.sprintiq.pms.common.domain.EntityType;

import java.util.UUID;

/**
 * Told when the conversation on a work item changes.
 *
 * <p>Declared here and implemented by whoever cares, the mirror of
 * {@code CommentThreadReader}: that one lets the tasks module ask this one a question,
 * this one lets it hear an answer without the comments module knowing what a task is.</p>
 *
 * <p>It exists because a risk charge that only ever goes up is a broken risk charge. The
 * sprint is charged for a question nobody answered; the moment somebody answers it, the
 * charge has to go, and the only event that reliably says so is the reply being posted.
 * Waiting for the next board refresh would leave the ledger claiming a stale silence, and
 * a client-side re-report cannot be relied on — the tab that would send it may well have
 * been closed by the person who just replied.</p>
 */
public interface WorkItemThreadListener {

    /**
     * A comment or reply has landed on this item, or one has been removed.
     *
     * <p>Deliberately not told which, or by whom. The listener re-reads the thread
     * either way — an event carrying "this was a reply by Sam" would let the two sides
     * disagree about what counts as an answer, which is exactly the judgement that must
     * live in one place.</p>
     */
    void threadChanged(EntityType entityType, UUID entityId);
}
