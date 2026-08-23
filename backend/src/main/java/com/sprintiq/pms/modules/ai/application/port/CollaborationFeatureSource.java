package com.sprintiq.pms.modules.ai.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How a sprint's team is coordinating, for the communication-and-collaboration
 * classifier (M2).
 *
 * <p>A separate port from {@link SprintFeatureSource} because the two are answered from
 * different tables by different modules. Sprint features are points, dates and capacity;
 * these are comment threads and their timestamps, which the sprints module has no
 * business knowing about.</p>
 *
 * <p>The window is passed in rather than derived here. The readings are all "per sprint
 * so far" and the caller already holds the sprint's dates — resolving them a second
 * time would mean the risk model and the burndown could disagree about when the sprint
 * started, on the same screen, for a sprint whose dates were edited.</p>
 */
public interface CollaborationFeatureSource {

    /**
     * The five readings the model scores, plus the counts they were derived from.
     *
     * <p>The counts are not passed to the model. They are here so the UI can say
     * "2.4 comments across 15 tasks" rather than only "2.4", and so a score computed
     * from three comments on two tasks can be recognised as the thin evidence it is.</p>
     *
     * @param avgResponseTimeHours mean gap between consecutive comments by *different*
     *                             people on the same item. Null when no thread in the
     *                             sprint has two participants, which is not the same as
     *                             an instant reply and must not be reported as zero.
     * @param elapsedDays          whole days of the sprint that have already passed.
     *                             Every count above is measured over these and no more,
     *                             so a sprint planned for next month is not charged for
     *                             a month of silence.
     * @param inactiveDays         how many of those had no recorded activity at all —
     *                             no comment, no task started, none completed.
     * @param longestSilenceDays   the longest unbroken run of those. Two separate quiet
     *                             days and two consecutive ones are the same
     *                             {@code inactiveDays} and different situations.
     * @param unansweredThreads    threads whose last comment is over a day old and was
     *                             never replied to by anyone else.
     */
    record SprintCollaboration(long taskCount,
                               long commentCount,
                               BigDecimal commentsPerTask,
                               BigDecimal avgResponseTimeHours,
                               int elapsedDays,
                               int inactiveDays,
                               int longestSilenceDays,
                               long blockedTasks,
                               long reopenedTasks,
                               long unansweredThreads,
                               long contributorCount) {

        /**
         * Whether there is anything here worth scoring.
         *
         * <p>Three of the five readings are healthiest at zero, so an untouched sprint
         * produces a well-formed feature vector that scores as a well-run one. The
         * model cannot tell "nobody is blocked" from "nobody has done anything", and it
         * should not be asked to.</p>
         *
         * <p>The second clause is the one that matters. A sprint with eighteen tasks,
         * three days gone and not one comment on any of them is the single clearest
         * case this model exists to catch — so elapsed time with work in it counts as
         * evidence, not just recorded activity. Requiring a comment before scoring
         * would mean the sprints with the worst communication were the ones that never
         * got assessed.</p>
         */
        public boolean hasSignal() {
            return commentCount > 0 || blockedTasks > 0 || reopenedTasks > 0
                    || (taskCount > 0 && elapsedDays > 0);
        }

        /** A sprint with no tasks at all — nothing to read, and nothing to score. */
        public static SprintCollaboration empty() {
            return new SprintCollaboration(0, 0, BigDecimal.ZERO, null, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * @param windowStart when the sprint began, or when its first item was created for a
     *                    sprint with no start date
     * @param windowEnd   now for a sprint in flight, its end date for a closed one —
     *                    never the future, or every unstarted day counts as silence
     */
    SprintCollaboration collaborationFor(UUID sprintId, Instant windowStart, Instant windowEnd);

    /**
     * One task, reduced to the timing facts the watch rules need.
     *
     * @param taskId          the task
     * @param title           for the ledger entry, so the reason reads as a sentence
     * @param status          TODO / IN_PROGRESS / BLOCKED / DONE / …
     * @param estimateMinutes null when nobody sized it — the deadline rules then have no
     *                        clock to measure against and skip the task entirely
     * @param createdAt       when the clock starts
     * @param commentCount    comments on this task
     * @param lastCommentAt   null when there are none
     */
    record WatchedTask(UUID taskId,
                       String title,
                       String status,
                       Integer estimateMinutes,
                       Instant createdAt,
                       long commentCount,
                       Instant lastCommentAt) {

        /** Half the estimated time from creation — the point the timing rules check. */
        public Instant halfTimeMark() {
            if (estimateMinutes == null || estimateMinutes <= 0 || createdAt == null) {
                return null;
            }
            return createdAt.plusSeconds(estimateMinutes * 30L);
        }

        /** The whole estimate from creation — the point a task is overdue. */
        public Instant deadline() {
            if (estimateMinutes == null || estimateMinutes <= 0 || createdAt == null) {
                return null;
            }
            return createdAt.plusSeconds(estimateMinutes * 60L);
        }

        public boolean isFinished() {
            return "DONE".equals(status) || "CANCELLED".equals(status);
        }

        public boolean hasStarted() {
            return !"TODO".equals(status);
        }
    }

    /**
     * The tasks of one sprint, for the between-assessment watch.
     *
     * <p>Separate from {@link #collaborationFor} because the two answer different
     * questions: that one aggregates a sprint into five model inputs, this one needs
     * each task individually so a penalty can name the task it came from and be
     * withdrawn when that task recovers.</p>
     */
    List<WatchedTask> watchedTasks(UUID sprintId);
}
