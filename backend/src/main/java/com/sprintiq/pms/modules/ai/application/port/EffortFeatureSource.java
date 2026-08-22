package com.sprintiq.pms.modules.ai.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recorded effort — the only non-estimated quantity any of these models gets to see.
 *
 * <p>Mid-sprint cost and over-budget risk both hinge on it. Everything else in the
 * feature set is a plan or a guess; logged minutes are what actually happened, which is
 * why the mid-sprint forecast can carry a much narrower error margin than the initial
 * one.</p>
 */
public interface EffortFeatureSource {

    record UserEffort(UUID userId, long minutes, long overtimeMinutes) {}

    /** Minutes logged against a sprint's tasks. */
    long sprintEffortMinutes(UUID sprintId);

    /** The share of those minutes logged outside contracted hours. */
    long sprintOvertimeMinutes(UUID sprintId);

    /** Logged sprint effort grouped by author so actual cost can use each person's rate. */
    List<UserEffort> sprintEffortByUser(UUID sprintId);

    /** Remaining estimated minutes on open tasks in the sprint. */
    long sprintRemainingEstimateMinutes(UUID sprintId);

    /** Original estimated minutes across all non-deleted sprint tasks. */
    long sprintPlannedEstimateMinutes(UUID sprintId);

    /** Tasks currently attached to the sprint. */
    long sprintTaskCount(UUID sprintId);

    /** Required skill names across tasks in the sprint. */
    List<String> sprintRequiredSkills(UUID sprintId);

    /** Open assigned work in the project, used as the concurrency cost signal. */
    long concurrentAssignments(UUID projectId);

    /** Current open-work load per user on a project, as a percentage of one sprint. */
    Map<UUID, BigDecimal> loadByUser(UUID projectId);
}
