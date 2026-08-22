package com.sprintiq.pms.modules.projects.web.dto;

import com.sprintiq.pms.modules.projects.domain.ChangeImpactDirection;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The requirement-change register: what moved, who asked, and what it cost the sprint. */
public final class RequirementChangeDtos {

    private RequirementChangeDtos() {
    }

    @Schema(name = "LogRequirementChangeRequest")
    public record LogRequirementChangeRequest(
            @NotNull RequirementChangeType changeType,

            @Schema(description = "Ignored for SCOPE_ADDED and SCOPE_REMOVED, whose sign is inherent")
            ChangeImpactDirection impactDirection,

            @Schema(description = "The sprint in flight. Omit when nothing is running — "
                    + "a change logged outside a sprint costs no sprint any risk.")
            UUID sprintId,

            @Schema(description = "The requirement being revised. Bumps its churn counter when set.")
            UUID requirementId,

            UUID storyId,

            @Size(max = 4000) String description,

            @Schema(description = "The account that asked, when the requester has one")
            UUID requestedBy,

            @Schema(description = "Free text for a stakeholder with no account here")
            @Size(max = 120) String requestedByName) {}

    @Schema(name = "RequirementChangeResponse")
    public record RequirementChangeResponse(UUID id,
                                            UUID projectId,
                                            UUID sprintId,
                                            UUID requirementId,
                                            String requirementCode,
                                            UUID storyId,
                                            RequirementChangeType changeType,
                                            ChangeImpactDirection impactDirection,
                                            String description,
                                            UUID requestedBy,
                                            String requestedByName,
                                            /** Points this change added to the sprint's requirement-change risk. */
                                            BigDecimal riskDelta,
                                            Instant changedAt) {}

    /**
     * What one change type will do before it is logged.
     *
     * <p>Served rather than duplicated in the client, because the weights are the
     * definition of the vocabulary and a UI that predicted "+8" while the server
     * recorded "+5" would be worse than one that said nothing.</p>
     */
    @Schema(name = "RequirementChangeTypeInfo")
    public record RequirementChangeTypeInfo(RequirementChangeType changeType,
                                            boolean directionFixed,
                                            BigDecimal increaseDelta,
                                            BigDecimal decreaseDelta,
                                            String increaseHint,
                                            String decreaseHint) {}

    @Schema(name = "RequirementChangeSummary",
            description = "The register for one sprint, with the churn totals a retrospective reads")
    public record RequirementChangeSummary(UUID sprintId,
                                           int totalChanges,
                                           int increasingChanges,
                                           int decreasingChanges,
                                           BigDecimal netRiskDelta,
                                           List<RequirementChangeResponse> changes) {}
}
