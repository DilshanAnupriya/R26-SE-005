package com.sprintiq.pms.modules.tasks.application;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.modules.comments.application.port.WorkItemThreadListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Keeps a task's unanswered-comment charge honest as its thread moves.
 *
 * <p>Re-evaluates rather than adjusting: {@code registerUnansweredComment} already holds
 * the rule for what counts as answered and how much a silence is worth, and a second
 * implementation of that rule here is how the badge on the card and the row in the ledger
 * start disagreeing.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskThreadListener implements WorkItemThreadListener {

    private final TaskService taskService;

    @Override
    @Transactional
    public void threadChanged(EntityType entityType, UUID entityId) {
        if (entityType != EntityType.TASK) {
            return;
        }
        try {
            taskService.registerUnansweredComment(entityId);
        } catch (RuntimeException failure) {
            // Never fail the comment for this. Somebody has written something and it is
            // saved; a risk-ledger revision that could not be applied is a background
            // bookkeeping problem, and throwing here would lose their words to it.
            log.warn("Could not re-score the unanswered-comment risk for task {}", entityId, failure);
        }
    }
}
