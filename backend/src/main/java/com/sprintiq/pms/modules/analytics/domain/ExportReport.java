package com.sprintiq.pms.modules.analytics.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The reports that can be exported.
 *
 * <p>An enum rather than a free-text name. The report name selects which query runs, so
 * accepting an arbitrary string would mean validating it anyway — and the enum makes the
 * set greppable and the switch in the generator exhaustive.</p>
 *
 * <p>{@code scope} records what each one needs, so the request can be rejected at the
 * boundary rather than failing inside a worker thread ten seconds later.</p>
 */
@Getter
@RequiredArgsConstructor
public enum ExportReport {

    VELOCITY("velocity", Scope.PROJECT),
    BURNDOWN("burndown", Scope.SPRINT),
    CYCLE_TIME("cycle-time", Scope.PROJECT),
    LEAD_TIME("lead-time", Scope.PROJECT),
    PRODUCTIVITY("productivity", Scope.PROJECT),
    WORKLOAD_BALANCE("workload-balance", Scope.PROJECT),
    COMPLETION_RATE("completion-rate", Scope.PROJECT),
    BACKLOG("backlog", Scope.PROJECT);

    public enum Scope { PROJECT, SPRINT }

    private final String slug;
    private final Scope scope;

    public boolean needsProject() {
        return scope == Scope.PROJECT;
    }

    public boolean needsSprint() {
        return scope == Scope.SPRINT;
    }
}
