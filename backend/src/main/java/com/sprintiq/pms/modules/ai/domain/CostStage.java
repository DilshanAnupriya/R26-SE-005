package com.sprintiq.pms.modules.ai.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * When a cost forecast was produced.
 *
 * <p>Both stages are planning instruments, not accounting. The initial forecast has no
 * actuals to work from and a correspondingly wide error margin; the mid-sprint one
 * replaces guesswork about effort with recorded time and narrows accordingly. Showing
 * them on one chart is the point — the narrowing <em>is</em> the information.</p>
 */
@Getter
@RequiredArgsConstructor
public enum CostStage {

    INITIAL(AiModelCode.M4_COST_INITIAL),
    MID_SPRINT(AiModelCode.M4_COST_MID);

    private final AiModelCode modelCode;
}
