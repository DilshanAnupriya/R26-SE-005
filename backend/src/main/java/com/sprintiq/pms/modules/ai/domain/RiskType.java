package com.sprintiq.pms.modules.ai.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The two questions Model 2 answers about a sprint. */
@Getter
@RequiredArgsConstructor
public enum RiskType {

    OVER_BUDGET(AiModelCode.M2_OVER_BUDGET, "Sprint exceeds its effort budget"),
    REQUIREMENT_CHANGE(AiModelCode.M2_REQUIREMENT_CHANGE, "Requirements churn significantly mid-sprint");

    private final AiModelCode modelCode;
    private final String question;
}
