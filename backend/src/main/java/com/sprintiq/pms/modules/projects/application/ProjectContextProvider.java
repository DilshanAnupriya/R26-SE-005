package com.sprintiq.pms.modules.projects.application;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.projects.domain.Project;
import com.sprintiq.pms.modules.projects.domain.ProjectMemberRepository;
import com.sprintiq.pms.modules.projects.domain.ProjectRepository;
import com.sprintiq.pms.modules.projects.domain.RequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Project-level context for the models: the workspace that owns the feature flags, the
 * budget a cost forecast is measured against, and the requirement churn that drives the
 * M2 requirement-change classifier.
 *
 * <p>Churn is the interesting one. The requirements register already counts changes made
 * after approval separately from ordinary drafting edits, because those are the ones that
 * wreck a sprint. Passing the raw edit count instead would tell the model that a
 * well-specified project with an active author is as risky as one whose scope keeps
 * moving after sign-off.</p>
 */
@Component
@RequiredArgsConstructor
public class ProjectContextProvider implements ProjectContextSource {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final RequirementRepository requirementRepository;
    /** Measures velocity and completion rate rather than trusting the stored figures. */
    private final ProjectDeliveryHistorySource deliveryHistory;

    @Override
    @Transactional(readOnly = true)
    public ProjectContext contextFor(UUID projectId) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        ProjectDeliveryHistorySource.DeliveryHistory history =
                deliveryHistory.historyFor(projectId);

        return new ProjectContext(project.getId(), project.getWorkspaceId(), project.getName(),
                project.getProjectKey(), project.getStatus().name(), project.getBudgetAmount(),
                project.getCurrency() == null ? "USD" : project.getCurrency(),
                requirementRepository.countByProjectIdAndDeletedAtIsNull(projectId),
                requirementRepository.totalChangeCount(projectId),
                memberRepository.countByProjectId(projectId),
                project.getDomain(),
                // One stack, first-listed — the model was trained on a single value and
                // the team's own ordering is a better answer than alphabetical.
                project.primaryTechStack(),
                project.getTeamSize(),
                project.getDevExperienceLevel(),
                velocityAvg(project, history),
                completionRate(project, history),
                project.getDefaultSprintDays());
    }

    /**
     * Measured velocity, or the figure somebody typed at project creation.
     *
     * <p>Measurement wins whenever there is one. The stored column is a starting
     * estimate for a project that has not run a sprint yet — useful exactly once, and
     * misleading from the first retrospective onward, because nothing in the product
     * ever updates it. Falling back to it rather than dropping it keeps a brand-new
     * project from handing the model a null it would answer with a training median.</p>
     */
    private BigDecimal velocityAvg(Project project,
                                   ProjectDeliveryHistorySource.DeliveryHistory history) {
        return history.hasHistory() && history.velocityAvg() != null
                ? history.velocityAvg()
                : project.getTeamVelocityAvg();
    }

    private BigDecimal completionRate(Project project,
                                      ProjectDeliveryHistorySource.DeliveryHistory history) {
        return history.hasHistory() && history.completionRate() != null
                ? history.completionRate()
                : project.getRecentCompletionRate();
    }
}
