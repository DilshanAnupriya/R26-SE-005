package com.sprintiq.pms.modules.ai.application.port;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Project-level context shared by several models: the requirement churn that drives the
 * M2 requirement-change classifier, and the budget the M4 forecast is measured against.
 *
 * <p>{@code postApprovalChanges} is the number that matters. Requirements edited while
 * still in draft are the process working; requirements edited after sign-off are the
 * thing that wrecks sprints, and the requirements register counts them separately for
 * exactly this reason.</p>
 */
public interface ProjectContextSource {

    record ProjectContext(UUID projectId,
                          /** Feature flags resolve workspace-first, so every gate needs this. */
                          UUID workspaceId,
                          String name,
                          String key,
                          String status,
                          BigDecimal budgetAmount,
                          String currency,
                          long requirementCount,
                          long postApprovalChanges,
                          long memberCount,

                          // ---- What the M1 model needs about the team and the stack ----
                          // Every one of these is nullable. Absent means "the project
                          // never said", and the model service substitutes the median of
                          // its training set — which is a different, and far safer,
                          // statement than a zero.
                          String domain,
                          String techStack,
                          Short teamSize,
                          String devExperienceLevel,
                          BigDecimal teamVelocityAvg,
                          BigDecimal recentCompletionRate,
                          Short sprintDurationDays) {

        /** Post-approval edits per requirement — churn normalised for project size. */
        public BigDecimal churnRate() {
            if (requirementCount <= 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(postApprovalChanges)
                    .divide(BigDecimal.valueOf(requirementCount), 4, java.math.RoundingMode.HALF_UP);
        }
    }

    ProjectContext contextFor(UUID projectId);
}
