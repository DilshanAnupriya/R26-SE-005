package com.sprintiq.pms.modules.projects.application;

import java.util.UUID;

/**
 * Published view of one board column, for the modules that place work items on a board.
 *
 * <p>{@code category} travels as text for the same reason {@link ProjectRef} carries a
 * text status: the enum stays owned by this module, and consumers only ever compare it
 * against the three phases they care about.</p>
 */
public record WorkflowStateView(
        UUID id,
        UUID projectId,
        String name,
        String code,
        String category,
        short position,
        Short wipLimit,
        String color) {

    public boolean isDone() {
        return "DONE".equals(category);
    }

    public boolean isTodo() {
        return "TODO".equals(category);
    }

    public boolean exceedsWipLimit(long itemCount) {
        return wipLimit != null && wipLimit > 0 && itemCount > wipLimit;
    }
}
