package com.sprintiq.pms.modules.projects.application;

import java.util.UUID;

/**
 * Just enough of a project to label it somewhere else.
 *
 * <p>Published for other modules so they never need a {@code ProjectRepository} —
 * a roadmap lane, a calendar event and a document breadcrumb all want the same three
 * fields, and none of them want the overview roll-up that {@code ProjectResponse}
 * pays for.</p>
 */
public record ProjectSummary(UUID id, String projectKey, String name) {}
