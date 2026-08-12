package com.sprintiq.pms.modules.projects.domain;

/**
 * RAG status shown on the portfolio view. Derived from schedule and scope signals by
 * {@code ProjectHealthCalculator}, but stored so a manager can override the computed
 * value — the override is the interesting datum, and recomputing it away would lose it.
 */
public enum ProjectHealth {

    ON_TRACK,
    AT_RISK,
    OFF_TRACK
}
