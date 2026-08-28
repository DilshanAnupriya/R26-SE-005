package com.sprintiq.pms.modules.projects.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Port a delivery module implements to say what a project has actually delivered.
 *
 * <p>Two of the story-point model's inputs — average velocity and recent completion
 * rate — were originally typed into the create-project form and then never touched
 * again. That is the wrong shape for both of them: they are measurements, not settings.
 * A number a human entered once describes the team that existed on the day the project
 * was created, and the model reads it as a description of the team delivering today.</p>
 *
 * <p>Declared here rather than in the sprints module for the same reason as
 * {@link ProjectStatisticsContributor}: the projects module states what it needs and the
 * module that owns sprint data satisfies it, so the dependency points inward and neither
 * module has to know the other's tables.</p>
 */
public interface ProjectDeliveryHistorySource {

    /**
     * What the last few completed sprints say about this team.
     *
     * @param velocityAvg     mean story points completed per sprint, or null when the
     *                        project has no completed sprint to average
     * @param completionRate  points completed over points committed across the same
     *                        window, 0 to 1, or null on no history
     * @param sprintsObserved how many completed sprints the figures came from — zero
     *                        means the two above are null and the caller should fall
     *                        back rather than treat absence as zero
     */
    record DeliveryHistory(BigDecimal velocityAvg, BigDecimal completionRate,
                           int sprintsObserved) {

        /** A project that has finished nothing yet. */
        public static DeliveryHistory none() {
            return new DeliveryHistory(null, null, 0);
        }

        public boolean hasHistory() {
            return sprintsObserved > 0;
        }
    }

    DeliveryHistory historyFor(UUID projectId);
}
