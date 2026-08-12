package com.sprintiq.pms.modules.backlog.domain;

/**
 * How two work items relate.
 *
 * <p>Only {@link #BLOCKS} constrains scheduling, and only it participates in cycle
 * detection: a loop in "relates to" is meaningless, whereas a loop in "blocks" means
 * nothing in the loop can ever start.</p>
 */
public enum DependencyType {

    /** Source must finish before target can start. */
    BLOCKS,

    /** Inverse of {@link #BLOCKS}, stored so either end can be authored naturally. */
    BLOCKED_BY,

    RELATES_TO,

    DUPLICATES;

    /** Whether this edge constrains order, and so must keep the graph acyclic. */
    public boolean isBlocking() {
        return this == BLOCKS || this == BLOCKED_BY;
    }

    /**
     * Normalises to the direction the graph is stored in. {@code A BLOCKED_BY B} is
     * persisted as {@code B BLOCKS A}, so cycle detection only ever walks one edge
     * type and cannot miss a loop authored from the other end.
     */
    public boolean requiresInversion() {
        return this == BLOCKED_BY;
    }

    public DependencyType inverted() {
        return switch (this) {
            case BLOCKS -> BLOCKED_BY;
            case BLOCKED_BY -> BLOCKS;
            case RELATES_TO -> RELATES_TO;
            case DUPLICATES -> DUPLICATES;
        };
    }
}
