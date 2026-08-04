package com.sprintiq.pms.common.domain;

/**
 * Shared priority scale for every work item (epic, story, task, requirement).
 *
 * <p>Lives in {@code common} rather than in one module's domain because all four
 * modules persist it: a module-owned copy would either be imported across module
 * boundaries — which the architecture rules forbid — or duplicated into four enums
 * that drift apart.</p>
 *
 * <p>{@code weight} gives sorting and AI feature vectors a numeric handle without
 * depending on declaration order.</p>
 */
public enum Priority {

    LOWEST(1),
    LOW(2),
    MEDIUM(3),
    HIGH(4),
    CRITICAL(5);

    private final int weight;

    Priority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public boolean atLeast(Priority other) {
        return weight >= other.weight;
    }
}
