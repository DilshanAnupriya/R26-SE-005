package com.sprintiq.pms.modules.backlog.application;

import java.util.UUID;

/**
 * How many tasks hang off a story.
 *
 * <p>Tasks belong to the tasks module, but the size of a story's breakdown is a signal
 * the backlog needs — a story already split into nine tasks is not a small story. Same
 * inversion as {@code WorkflowStateUsageCounter}: the module that needs the number
 * declares the question, the module that owns the rows answers it, and no repository
 * crosses a boundary.</p>
 */
public interface StoryTaskCounter {

    long countTasksForStory(UUID storyId);

    /**
     * The story's tasks, reduced to what a budget needs.
     *
     * @param taskCount      tasks hanging off the story
     * @param estimatedCount how many of those carry an estimate — the rest are invisible
     *                       to the budget, and a budget that looks comfortable only
     *                       because half the work is unsized is worse than no budget
     * @param estimatedMinutes their estimates, summed
     */
    record StoryTaskEffort(long taskCount, long estimatedCount, long estimatedMinutes) {

        public static StoryTaskEffort empty() {
            return new StoryTaskEffort(0, 0, 0);
        }
    }

    StoryTaskEffort effortForStory(UUID storyId);

    /**
     * Moves a story's tasks with it when the story is committed to a sprint, or back
     * to the backlog when it is pulled out.
     *
     * <p>A task's sprint is not decoration. Everything that reads a sprint's work reads
     * it — the cost model prices planned effort by summing task estimates for the
     * sprint, the board draws from it, the watch sweeps it. A story that moved into a
     * sprint while its tasks stayed behind is a sprint that looks committed and
     * forecasts at zero dollars, with nothing on screen to say why.</p>
     *
     * @param sprintId the sprint the story now belongs to, or null when it has returned
     *                 to the backlog
     */
    void moveStoryTasksToSprint(UUID storyId, UUID sprintId);
}
