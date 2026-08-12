package com.sprintiq.pms.modules.projects.application;

import java.util.UUID;

/**
 * Port a delivery module implements to contribute its slice of a project's overview.
 *
 * <p>The alternative — having {@code ProjectService} query the backlog, task and sprint
 * tables itself — would make the projects module depend on three other modules'
 * persistence models, which is exactly the coupling that stops a module being lifted
 * into its own service later. Here the dependency points inward: contributors know
 * about this port, the port knows nothing about them, and Spring injects whichever
 * implementations are on the classpath.</p>
 *
 * <p>Implementations must be side-effect free and safe to call inside a read-only
 * transaction.</p>
 */
public interface ProjectStatisticsContributor {

    /**
     * This contributor's partial view of the project. Fields the implementing module
     * does not own are left at their zero value so {@link ProjectStatistics#merge}
     * can fold the results in any order.
     */
    ProjectStatistics contribute(UUID projectId);
}
