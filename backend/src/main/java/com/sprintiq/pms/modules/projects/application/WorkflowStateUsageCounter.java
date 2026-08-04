package com.sprintiq.pms.modules.projects.application;

import java.util.UUID;

/**
 * Port a module implements to report how many of its items currently sit in a board
 * column.
 *
 * <p>Deleting a column that still holds work would strand those items in a state that
 * no longer exists — they would vanish from the board with no way to get them back
 * short of a database edit. This module owns the columns but has no idea what occupies
 * them, so it asks: backlog counts stories, tasks counts tasks, and the sum decides
 * whether the delete is safe.</p>
 *
 * <p>Also used for the board's WIP-limit badges.</p>
 */
public interface WorkflowStateUsageCounter {

    /** Live, non-deleted items of this module's kind currently in the given column. */
    long countItemsInState(UUID workflowStateId);
}
