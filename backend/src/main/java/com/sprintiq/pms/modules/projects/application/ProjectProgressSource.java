package com.sprintiq.pms.modules.projects.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How far through its work each project is, for a whole page of projects at once.
 *
 * <p>Separate from {@link ProjectStatisticsContributor} because the shapes differ in the
 * way that matters: the overview asks about one project and can afford to fan out across
 * every contributor, while the projects list renders twenty cards and cannot. Asking the
 * per-project port twenty times would be the same page with sixty extra queries behind
 * it.</p>
 *
 * <p>Declared here and implemented by the module that owns story points, so the projects
 * module still never learns what a story is.</p>
 */
public interface ProjectProgressSource {

    /**
     * Delivered against committed, in points.
     *
     * <p>Points rather than story counts: a project that has finished nine one-point
     * chores and not started its thirteen-point migration is not 90% done, and a card
     * that says so is worse than a card that says nothing.</p>
     */
    record Progress(BigDecimal totalPoints, BigDecimal completedPoints) {

        public static Progress none() {
            return new Progress(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /**
         * Completion as a percentage, 0–100.
         *
         * <p>Zero when nothing is pointed. A project whose stories are all unestimated
         * has no measurable progress, and reporting 100% because the numerator and
         * denominator are both zero would be the worst available answer.</p>
         */
        public BigDecimal pct() {
            if (totalPoints == null || totalPoints.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal done = completedPoints == null ? BigDecimal.ZERO : completedPoints;
            return done.multiply(BigDecimal.valueOf(100))
                    .divide(totalPoints, 2, RoundingMode.HALF_UP)
                    .min(BigDecimal.valueOf(100));
        }
    }

    /** One query for the whole page. Projects with no stories may be absent from the map. */
    Map<UUID, Progress> progressFor(Collection<UUID> projectIds);
}
