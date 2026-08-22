package com.sprintiq.pms.modules.ai.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The eight registry entries behind the product's four AI capabilities.
 *
 * <p>Four models, eight codes: M1 answers two different questions (how big is this
 * story, how much will this team deliver), M2 answers three (does the effort overrun,
 * do the requirements churn, is the team still coordinating) and M4 answers the same
 * question at two different moments (before the sprint, halfway through it). Those are
 * separate feature vectors and separate accuracy stories, so they are separate registry
 * rows — collapsing them would make "how accurate is M4" unanswerable.</p>
 *
 * <p>{@link #flagKey()} is the feature flag that gates the capability, not the code.
 * A workspace turns off <em>cost forecasting</em>, not "M4_COST_MID specifically".</p>
 */
@Getter
@RequiredArgsConstructor
public enum AiModelCode {

    M1_STORY_POINTS("ai.story_points", "Story point estimation"),
    M1_VELOCITY("ai.story_points", "Sprint velocity prediction"),
    M2_OVER_BUDGET("ai.risk", "Over-budget risk"),
    M2_REQUIREMENT_CHANGE("ai.risk", "Requirement change risk"),
    M2_COMMUNICATION_COLLABORATION("ai.risk", "Communication and collaboration risk"),
    M3_ALLOCATION("ai.allocation", "Resource allocation ranking"),
    M4_COST_INITIAL("ai.cost", "Initial cost forecast"),
    M4_COST_MID("ai.cost", "Mid-sprint cost forecast");

    private final String flagKey;
    private final String displayName;

    /** The product-facing family — what a user would call it. */
    public String family() {
        return name().substring(0, 2);
    }
}
