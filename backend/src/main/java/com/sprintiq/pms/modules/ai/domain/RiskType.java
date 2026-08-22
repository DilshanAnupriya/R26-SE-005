package com.sprintiq.pms.modules.ai.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The three questions Model 2 answers about a sprint.
 *
 * <p>The first two ask whether the plan was wrong. The third asks whether the team is
 * still working as one, which is a different kind of failure and the one that tends to
 * cause the other two rather than accompany them — a sprint where nobody is talking
 * produces both the overrun and the churn a fortnight later.</p>
 */
@Getter
@RequiredArgsConstructor
public enum RiskType {

    OVER_BUDGET(AiModelCode.M2_OVER_BUDGET, "Sprint exceeds its effort budget"),
    REQUIREMENT_CHANGE(AiModelCode.M2_REQUIREMENT_CHANGE, "Requirements churn significantly mid-sprint"),
    COMMUNICATION_COLLABORATION(AiModelCode.M2_COMMUNICATION_COLLABORATION,
            "The team has stopped coordinating: slow replies, silence, blockers and rework");

    private final AiModelCode modelCode;
    private final String question;
}
