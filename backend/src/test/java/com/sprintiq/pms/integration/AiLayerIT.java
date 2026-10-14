package com.sprintiq.pms.integration;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.modules.ai.application.*;
import com.sprintiq.pms.modules.ai.domain.*;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.*;
import com.sprintiq.pms.modules.backlog.application.StoryService;
import com.sprintiq.pms.modules.backlog.domain.StoryRepository;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.CreateStoryRequest;
import com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos.StoryResponse;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.organizations.domain.WorkspaceRepository;
import com.sprintiq.pms.modules.projects.application.ProjectService;
import com.sprintiq.pms.modules.projects.application.RequirementChangeService;
import com.sprintiq.pms.modules.projects.domain.ChangeImpactDirection;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeType;
import com.sprintiq.pms.modules.projects.web.dto.RequirementChangeDtos.LogRequirementChangeRequest;
import com.sprintiq.pms.modules.projects.web.dto.RequirementChangeDtos.RequirementChangeResponse;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.CreateProjectRequest;
import com.sprintiq.pms.modules.projects.web.dto.ProjectDtos.ProjectResponse;
import com.sprintiq.pms.modules.settings.application.FeatureFlagService;
import com.sprintiq.pms.modules.sprints.application.SprintService;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.AddSprintItemsRequest;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.CreateSprintRequest;
import com.sprintiq.pms.modules.sprints.web.dto.SprintDtos.SprintResponse;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * The AI layer end to end against real Postgres, driven through the application services.
 *
 * <p>What is being proved here is not that the heuristics produce good numbers — the mock
 * client's own unit tests cover the arithmetic. It is the contract around them: a
 * suggestion never silently becomes an estimate, accepting one does, every call leaves a
 * prediction row with the exact features it used, identical features are served from
 * cache, and a switched-off capability refuses rather than returning an empty panel.</p>
 */
@Transactional
class AiLayerIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectService projectService;
    @Autowired StoryService storyService;
    @Autowired StoryRepository storyRepository;
    @Autowired SprintService sprintService;

    @Autowired EstimationService estimationService;
    @Autowired RiskService riskService;
    @Autowired RequirementChangeService requirementChangeService;
    @Autowired SprintRiskAdjustmentService adjustmentService;
    @Autowired RiskRemediationService remediationService;
    @Autowired CostForecastService costForecastService;
    @Autowired AiFeedbackService feedbackService;
    @Autowired AiInsightsService insightsService;
    @Autowired AiModelAdminService modelAdminService;
    @Autowired AiPredictionRepository predictionRepository;
    @Autowired FeatureFlagService featureFlags;

    private UUID workspaceId;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ fixtures

    private UUID newUser(String label) {
        String email = "%s+%s@example.test".formatted(label, UUID.randomUUID());
        User user = User.builder()
                .email(email)
                .emailNormalized(email.toLowerCase(Locale.ROOT))
                .fullName("Test %s".formatted(label))
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(java.time.Instant.now())
                .build();
        return userRepository.saveAndFlush(user).getId();
    }

    private ProjectResponse newProject(String projectName) {
        UUID userId = newUser("owner");
        Organization organization = provisioningService.provisionOrganization("Acme Robotics", userId);

        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organization.getId())
                .userId(userId)
                .permissions(Set.of())
                .systemContext(false)
                .build());

        workspaceId = workspaceRepository
                .findByOrganizationIdAndDeletedAtIsNull(organization.getId())
                .get(0).getId();

        return projectService.create(new CreateProjectRequest(workspaceId, projectName, null,
                "Created by AiLayerIT", null, null, BigDecimal.valueOf(90000), "USD", null, null,
                "fintech", List.of("java"), (short) 14, List.of(), List.of(), null, null, null, null));
    }

    private StoryResponse newStory(UUID projectId, String title, String description,
                                   String criteria, BigDecimal points) {
        return storyService.create(new CreateStoryRequest(projectId, null, null, title, description,
                criteria, null, null, null, points, null, null, null, null, null, null, null));
    }

    // ------------------------------------------------------- M1 · the accept loop

    @Test
    @DisplayName("a suggestion is recorded against the story without touching its estimate")
    void suggestionDoesNotOverwriteTheHumanEstimate() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Add biometric login",
                "Support Face ID and fingerprint on the login screen.",
                "Face ID enrols\nFingerprint enrols\nFallback to passcode", null);

        StoryPointEstimateResponse estimate = estimationService.estimate(story.id());

        assertThat(estimate.suggestedPoints()).isNotNull();
        assertThat(estimate.prediction().drivers()).isNotEmpty();
        assertThat(estimate.prediction().explanation()).isNotBlank();

        var stored = storyRepository.findByIdAndDeletedAtIsNull(story.id()).orElseThrow();
        assertThat(stored.getAiStoryPoints()).isEqualByComparingTo(estimate.suggestedPoints());
        assertThat(stored.getAiPredictionId()).isEqualTo(estimate.prediction().predictionId());
        assertThat(stored.getStoryPoints()).as("the human estimate is untouched").isNull();
        assertThat(stored.getEstimateSource().name()).isEqualTo("UNESTIMATED");
    }

    @Test
    @DisplayName("accepting a suggestion applies it and records the verdict as training signal")
    void acceptingASuggestionAppliesIt() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Export statements",
                "Let customers export a PDF statement.", "PDF renders\nEmail delivery", null);

        StoryPointEstimateResponse estimate = estimationService.estimate(story.id());
        FeedbackResponse feedback = feedbackService.submit(estimate.prediction().predictionId(),
                new SubmitFeedbackRequest(FeedbackAction.ACCEPTED, null, null, null));

        assertThat(feedback.action()).isEqualTo(FeedbackAction.ACCEPTED);
        assertThat(feedback.delta()).isEqualByComparingTo(BigDecimal.ZERO);

        var stored = storyRepository.findByIdAndDeletedAtIsNull(story.id()).orElseThrow();
        assertThat(stored.getStoryPoints()).isEqualByComparingTo(estimate.suggestedPoints());
        assertThat(stored.getEstimateSource().name()).isEqualTo("AI_ACCEPTED");
    }

    @Test
    @DisplayName("an override records the gap between the model and the team")
    void overrideRecordsTheDelta() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Rewrite the settlement engine", null, null, null);

        StoryPointEstimateResponse estimate = estimationService.estimate(story.id());
        FeedbackResponse feedback = feedbackService.submit(estimate.prediction().predictionId(),
                new SubmitFeedbackRequest(FeedbackAction.OVERRIDDEN, BigDecimal.valueOf(21),
                        "Touches three services", null));

        assertThat(feedback.finalValue()).isEqualByComparingTo(BigDecimal.valueOf(21));
        assertThat(feedback.delta())
                .isEqualByComparingTo(BigDecimal.valueOf(21).subtract(estimate.suggestedPoints()));

        var stored = storyRepository.findByIdAndDeletedAtIsNull(story.id()).orElseThrow();
        assertThat(stored.getStoryPoints()).isEqualByComparingTo(BigDecimal.valueOf(21));
        assertThat(stored.getEstimateSource().name()).isEqualTo("AI_OVERRIDDEN");
    }

    @Test
    @DisplayName("an override without a replacement value is refused")
    void overrideMustSayWhatTheValueShouldBe() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Something", null, null, null);
        StoryPointEstimateResponse estimate = estimationService.estimate(story.id());

        assertThatThrownBy(() -> feedbackService.submit(estimate.prediction().predictionId(),
                new SubmitFeedbackRequest(FeedbackAction.OVERRIDDEN, null, null, null)))
                .hasMessageContaining("what the value should be");
    }

    @Test
    @DisplayName("dismissing leaves the story alone")
    void dismissalChangesNothing() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Nice to have", null, null, null);
        StoryPointEstimateResponse estimate = estimationService.estimate(story.id());

        feedbackService.submit(estimate.prediction().predictionId(),
                new SubmitFeedbackRequest(FeedbackAction.DISMISSED, null, "Not sizing this yet", null));

        var stored = storyRepository.findByIdAndDeletedAtIsNull(story.id()).orElseThrow();
        assertThat(stored.getStoryPoints()).isNull();
        assertThat(stored.getEstimateSource().name()).isEqualTo("UNESTIMATED");
    }

    // ------------------------------------------------------------- audit and cache

    @Test
    @DisplayName("every prediction stores the exact feature vector it was computed from")
    void predictionsArePersistedWithTheirFeatures() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Add 2FA", "SMS and TOTP.", "SMS\nTOTP", null);

        StoryPointEstimateResponse estimate = estimationService.estimate(story.id());
        PredictionDetailResponse detail =
                feedbackService.detail(estimate.prediction().predictionId());

        assertThat(detail.features()).containsKeys("acceptance_criteria_count", "word_count",
                "dependency_count", "project_median_points", "comparable_count");
        assertThat(detail.features().get("acceptance_criteria_count").toString()).isEqualTo("2");
        assertThat(detail.prediction().modelCode()).isEqualTo(AiModelCode.M1_STORY_POINTS);
        assertThat(detail.prediction().modelVersion()).isNotBlank();
    }

    @Test
    @DisplayName("re-asking about an unchanged story is served from cache, not from the model")
    void identicalFeaturesHitTheCache() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Cache me", "Stable description.", "One", null);

        StoryPointEstimateResponse first = estimationService.estimate(story.id());
        StoryPointEstimateResponse second = estimationService.estimate(story.id());

        assertThat(first.prediction().cacheHit()).isFalse();
        assertThat(second.prediction().cacheHit()).isTrue();
        assertThat(second.suggestedPoints()).isEqualByComparingTo(first.suggestedPoints());
        // Both are recorded, so usage stays countable.
        assertThat(predictionRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                EntityType.STORY, story.id())).hasSize(2);
    }

    @Test
    @DisplayName("editing the story invalidates its cached estimate without any explicit eviction")
    void changingTheStoryProducesAFreshPrediction() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Small", "One line.", "One", null);

        StoryPointEstimateResponse before = estimationService.estimate(story.id());

        storyService.update(story.id(), new com.sprintiq.pms.modules.backlog.web.dto.BacklogDtos
                .UpdateStoryRequest(null, "Much bigger now, with far more detail than before",
                "One\nTwo\nThree\nFour\nFive\nSix", null, null, null, null, null, null, null,
                null, null));

        StoryPointEstimateResponse after = estimationService.estimate(story.id());

        assertThat(after.prediction().cacheHit()).isFalse();
        assertThat(after.suggestedPoints()).isGreaterThanOrEqualTo(before.suggestedPoints());
    }

    // ---------------------------------------------------------------- feature gate

    @Test
    @DisplayName("a switched-off capability refuses instead of returning an empty panel")
    void disabledCapabilityIsRefusedLoudly() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Gated", null, null, null);

        featureFlags.set(workspaceId, FeatureFlagService.AI_STORY_POINTS, false, null);

        assertThatThrownBy(() -> estimationService.estimate(story.id()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("switched off");
    }

    @Test
    @DisplayName("the insights dashboard names disabled capabilities instead of hiding them")
    void insightsReportDisabledCapabilities() {
        ProjectResponse project = newProject("Mobile Banking");
        featureFlags.set(workspaceId, FeatureFlagService.AI_COST, false, null);

        AiInsightsResponse insights = insightsService.forProject(project.id());

        assertThat(insights.disabledCapabilities()).contains(FeatureFlagService.AI_COST);
        assertThat(insights.cost()).isNull();
    }

    // ------------------------------------------------------- sprint-level models

    @Test
    @DisplayName("risk assessment stores a score, a severity and the drivers behind it")
    void riskAssessmentIsPersistedWithItsReasoning() {
        ProjectResponse project = newProject("Mobile Banking");
        SprintResponse sprint = newSprintWithWork(project.id());

        SprintRiskResponse risk = riskService.assess(sprint.id());

        assertThat(risk.overBudget()).isNotNull();
        assertThat(risk.overBudget().riskScore()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(risk.overBudget().severity()).isNotNull();
        assertThat(risk.overBudget().drivers()).isNotNull();
        assertThat(risk.requirementChange()).isNotNull();

        SprintRiskResponse reread = riskService.latest(sprint.id());
        assertThat(reread.overBudget().id()).isEqualTo(risk.overBudget().id());
    }

    @Test
    @DisplayName("requirement change is 0% until a requirement has actually moved")
    void requirementChangeStartsAtZero() {
        ProjectResponse project = newProject("Mobile Banking");
        SprintResponse sprint = newSprintWithWork(project.id());

        // A sprint with work on it and nothing changed: the model would have read its
        // planning columns and handed back a standing 10-20%. Nothing has churned, so the
        // figure is nought, and no model was asked for it.
        SprintRiskResponse fresh = riskService.assess(sprint.id());
        assertThat(fresh.requirementChange()).isNotNull();
        assertThat(fresh.requirementChange().riskScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fresh.requirementChange().predictionId()).isNull();
        assertThat(riskService.latest(sprint.id()).requirementChange().riskScore())
                .isEqualByComparingTo(BigDecimal.ZERO);

        // The first logged change brings the model in.
        requirementChangeService.log(project.id(), new LogRequirementChangeRequest(
                RequirementChangeType.SCOPE_ADDED, ChangeImpactDirection.INCREASE,
                sprint.id(), null, null, "A second payment provider", null, "Product owner"));
        SprintRiskResponse changed = riskService.assess(sprint.id());
        assertThat(changed.requirementChange().predictionId())
                .as("once a requirement has moved, the model scores the sprint again")
                .isNotNull();
        assertThat(changed.requirementChange().adjustmentDelta())
                .isEqualByComparingTo(BigDecimal.valueOf(8));
    }

    @Test
    @DisplayName("collaboration starts at 0% and still shows what the board charges")
    void collaborationBaselineCarriesBoardCharges() {
        ProjectResponse project = newProject("Mobile Banking");
        SprintResponse sprint = newSprintWithWork(project.id());

        // Nothing for the collaboration model to read. It used to come back unscored, and
        // an unscored gauge had nothing for a board charge to sit on.
        SprintRiskResponse fresh = riskService.assess(sprint.id());
        assertThat(fresh.communicationCollaboration()).isNotNull();
        assertThat(fresh.communicationCollaboration().riskScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fresh.communicationCollaboration().predictionId()).isNull();

        // A task added with nobody on it.
        adjustmentService.revise(sprint.id(), RiskType.COMMUNICATION_COLLABORATION,
                "TASK_UNASSIGNED:" + UUID.randomUUID(), BigDecimal.valueOf(3), "Nobody assigned to MB-1");
        assertThat(riskService.latest(sprint.id()).communicationCollaboration().adjustmentDelta())
                .as("the charge moves the gauge straight away")
                .isEqualByComparingTo(BigDecimal.valueOf(3));

        // And a second baseline does not swallow it.
        assertThat(riskService.assess(sprint.id()).communicationCollaboration().adjustmentDelta())
                .isEqualByComparingTo(BigDecimal.valueOf(3));
        assertThat(riskService.latest(sprint.id()).communicationCollaboration().adjustmentDelta())
                .isEqualByComparingTo(BigDecimal.valueOf(3));
    }

    @Test
    @DisplayName("a logged requirement change survives pressing Assess")
    void requirementChangeSurvivesReassessment() {
        ProjectResponse project = newProject("Mobile Banking");
        SprintResponse sprint = newSprintWithWork(project.id());

        riskService.assess(sprint.id());

        // Scope added mid-sprint: +8 on the register's own scale.
        requirementChangeService.log(project.id(), new LogRequirementChangeRequest(
                RequirementChangeType.SCOPE_ADDED, ChangeImpactDirection.INCREASE,
                sprint.id(), null, null, "A second payment provider", null, "Product owner"));

        BigDecimal charged = riskService.latest(sprint.id()).requirementChange().adjustmentDelta();
        assertThat(charged).isEqualByComparingTo(BigDecimal.valueOf(8));

        // The whole regression, in the two lines below. Re-scoring moves the cutoff, and
        // everything the models re-read for themselves falls behind it — but no feature
        // vector carries "a change was logged", so this one has to keep counting. It did
        // not, and the score fell back to its pre-change value the moment anybody pressed
        // Assess, which read as the risk fixing itself.
        SprintRiskResponse reassessed = riskService.assess(sprint.id());
        assertThat(reassessed.requirementChange().adjustmentDelta())
                .as("the Assess response itself has to carry the standing charge")
                .isEqualByComparingTo(BigDecimal.valueOf(8));

        // And the same figure on a fresh read, so the two paths cannot disagree about a
        // sprint nothing has happened to in between.
        assertThat(riskService.latest(sprint.id()).requirementChange().adjustmentDelta())
                .isEqualByComparingTo(BigDecimal.valueOf(8));
    }

    @Test
    @DisplayName("withdrawing that change gives the points back, across an assessment")
    void withdrawingAChangeReversesItAcrossAssess() {
        ProjectResponse project = newProject("Mobile Banking");
        SprintResponse sprint = newSprintWithWork(project.id());
        riskService.assess(sprint.id());

        RequirementChangeResponse logged = requirementChangeService.log(project.id(),
                new LogRequirementChangeRequest(RequirementChangeType.SCOPE_ADDED,
                        ChangeImpactDirection.INCREASE, sprint.id(), null, null,
                        "A second payment provider", null, "Product owner"));

        riskService.assess(sprint.id());
        requirementChangeService.delete(logged.id());

        // The reversal is standing too. If it expired at the cutoff while the charge it
        // reverses stood, withdrawing a change would leave the sprint paying for it for
        // the rest of the sprint.
        assertThat(riskService.assess(sprint.id()).requirementChange().adjustmentDelta())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("board evidence survives pressing Assess")
    void boardReadingsSurviveReassessment() {
        ProjectResponse project = newProject("Mobile Banking");
        SprintResponse sprint = newSprintWithWork(project.id());
        riskService.assess(sprint.id());

        adjustmentService.record(sprint.id(), RiskType.OVER_BUDGET,
                BigDecimal.valueOf(8), "Task blocked: MBA-1");
        assertThat(riskService.latest(sprint.id()).overBudget().adjustmentDelta())
                .isEqualByComparingTo(BigDecimal.valueOf(8));

        // Re-scoring used to fold this away, and the gauge fell straight back to where it
        // was before the card moved — the button appeared to undo the board. What the
        // board added since the last scoring is carried through the new one instead.
        assertThat(riskService.assess(sprint.id()).overBudget().adjustmentDelta())
                .as("the Assess response itself has to carry the board's charge")
                .isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(riskService.latest(sprint.id()).overBudget().adjustmentDelta())
                .as("and so does every read after it")
                .isEqualByComparingTo(BigDecimal.valueOf(8));
    }

    @Test
    @DisplayName("a ticked remediation step keeps its credit across Assess")
    void claimedStepSurvivesReassessment() {
        ProjectResponse project = newProject("Mobile Banking");
        // No sprint goal, which the model reads as a raising driver on a reading the
        // sprint can move — so there is a step on offer to claim. A goal is also
        // something ticking the box does not actually do, which is the case that broke:
        // the board is unchanged, so the next scoring reads exactly what it read before.
        SprintResponse sprint = newSprintWithoutAGoal(project.id());
        // A requirement-change history that nets to nothing. A sprint whose requirements
        // have never moved scores 0% on that model without consulting it, and then has no
        // step to offer; logging a change and withdrawing it brings the model in while
        // leaving the gauge at the step's own credit alone.
        RequirementChangeResponse logged = requirementChangeService.log(project.id(),
                new LogRequirementChangeRequest(RequirementChangeType.SCOPE_ADDED,
                        ChangeImpactDirection.INCREASE, sprint.id(), null, null,
                        "Logged and withdrawn", null, "Product owner"));
        requirementChangeService.delete(logged.id());
        riskService.assess(sprint.id());

        List<RiskRemediationStepResponse> offered = remediationService.stepsFor(sprint.id());
        assertThat(offered)
                .as("the model has to reward moving at least one reading for this to test anything")
                .isNotEmpty();
        RiskRemediationStepResponse step = offered.get(0);

        remediationService.toggle(sprint.id(), step.riskType(), step.stepKey(), true);
        BigDecimal claimed = deltaFor(riskService.latest(sprint.id()), step.riskType());
        assertThat(claimed).as("ticking takes the points off straight away").isNegative();

        // The regression this pins. Nothing on the board has moved, so the same step is
        // still on offer and the claim has to stand at the same figure. It used to fall
        // behind the new scoring's cutoff instead, and the points a team had just taken
        // off came straight back the moment anybody pressed Assess.
        assertThat(deltaFor(riskService.assess(sprint.id()), step.riskType()))
                .as("the Assess response itself has to carry the claim")
                .isEqualByComparingTo(claimed);

        // Same figure on a fresh read, so the two paths cannot disagree about a sprint
        // nothing has happened to in between.
        assertThat(deltaFor(riskService.latest(sprint.id()), step.riskType()))
                .isEqualByComparingTo(claimed);

        // And the tick is still shown. The state and the credit are the same ledger row,
        // so a box that unticked itself here would mean the gauge was paying for a claim
        // the panel had forgotten.
        assertThat(remediationService.stepsFor(sprint.id()))
                .filteredOn(candidate -> candidate.riskType() == step.riskType()
                                         && candidate.stepKey().equals(step.stepKey()))
                .singleElement()
                .extracting(RiskRemediationStepResponse::done)
                .isEqualTo(true);
    }

    /** The one model's figure out of the three-model response. */
    private BigDecimal deltaFor(SprintRiskResponse risk, RiskType riskType) {
        return switch (riskType) {
            case OVER_BUDGET -> risk.overBudget().adjustmentDelta();
            case REQUIREMENT_CHANGE -> risk.requirementChange().adjustmentDelta();
            case COMMUNICATION_COLLABORATION -> risk.communicationCollaboration().adjustmentDelta();
        };
    }

    @Test
    @DisplayName("a cost forecast always carries bounds and a variance against the budget")
    void costForecastReportsUncertaintyAndVariance() {
        ProjectResponse project = newProject("Mobile Banking");
        SprintResponse sprint = newSprintWithWork(project.id());

        CostForecastResponse forecast = costForecastService.forecast(sprint.id(), CostStage.INITIAL);

        assertThat(forecast.forecastAmount()).isPositive();
        assertThat(forecast.lowerBound()).isLessThan(forecast.forecastAmount());
        assertThat(forecast.upperBound()).isGreaterThan(forecast.forecastAmount());
        assertThat(forecast.currency()).isEqualTo("USD");
        assertThat(forecast.varianceVsBudgetPct()).isNotNull();
        assertThat(forecast.explanation()).isNotBlank();

        CostTrendPoint stored = costForecastService.sprintHistory(sprint.id()).getFirst();
        assertThat(stored.drivers()).isEqualTo(forecast.drivers());
        assertThat(stored.explanation()).isEqualTo(forecast.explanation());
        assertThat(stored.predictionId()).isEqualTo(forecast.predictionId());
    }

    @Test
    @DisplayName("a mid-sprint forecast asked for on day one falls back to the initial model")
    void midSprintForecastNeedsElapsedTimeToExtrapolateFrom() {
        ProjectResponse project = newProject("Mobile Banking");
        SprintResponse sprint = newSprintWithWork(project.id());

        CostForecastResponse forecast = costForecastService.forecast(sprint.id(), CostStage.MID_SPRINT);

        assertThat(forecast.stage()).isEqualTo(CostStage.INITIAL);
    }

    @Test
    @DisplayName("velocity prediction judges the commitment rather than just reporting a number")
    void velocityPredictionVerdictsTheCommitment() {
        ProjectResponse project = newProject("Mobile Banking");
        SprintResponse sprint = newSprintWithWork(project.id());

        VelocityPredictionResponse velocity = estimationService.predictVelocity(sprint.id());

        assertThat(velocity.predictedVelocity()).isNotNull();
        assertThat(velocity.commitmentVerdict())
                .isIn("OVER_COMMITTED", "STRETCHED", "BALANCED", "UNDER_COMMITTED", "UNKNOWN");
    }

    // --------------------------------------------------------------- admin view

    @Test
    @DisplayName("the model registry lists all seven entries with their flag state")
    void registryExposesEveryModel() {
        newProject("Mobile Banking");

        List<AiModelResponse> models = modelAdminService.list(workspaceId);

        assertThat(models).hasSize(AiModelCode.values().length);
        assertThat(models).extracting(AiModelResponse::code)
                .containsExactlyInAnyOrder(AiModelCode.values());
        assertThat(models).allSatisfy(model -> {
            assertThat(model.flagKey()).isNotBlank();
            assertThat(model.usage()).isNotNull();
        });
    }

    @Test
    @DisplayName("disabling a model in the registry stops it being called")
    void disablingAModelStopsInference() {
        ProjectResponse project = newProject("Mobile Banking");
        StoryResponse story = newStory(project.id(), "Blocked by registry", null, null, null);

        modelAdminService.update(AiModelCode.M1_STORY_POINTS,
                new UpdateAiModelRequest(false, null, null, null), workspaceId);

        assertThatThrownBy(() -> estimationService.estimate(story.id()))
                .isInstanceOf(ApplicationException.class);

        // Leave the shared registry as it was: it is global reference data, not tenant
        // state, so a test that disables a model would break every test after it.
        modelAdminService.update(AiModelCode.M1_STORY_POINTS,
                new UpdateAiModelRequest(true, null, null, null), workspaceId);
    }

    // ------------------------------------------------------------------ internals

    /** As {@link #newSprintWithWork}, minus the goal — a reading the models penalise. */
    private SprintResponse newSprintWithoutAGoal(UUID projectId) {
        StoryResponse first = newStory(projectId, "Committed A", "Detail.", "One\nTwo",
                BigDecimal.valueOf(5));
        StoryResponse second = newStory(projectId, "Committed B", "Detail.", "One",
                BigDecimal.valueOf(8));

        SprintResponse sprint = sprintService.create(new CreateSprintRequest(projectId,
                "Sprint 1", null, LocalDate.now(), LocalDate.now().plusDays(13),
                (short) 5, BigDecimal.valueOf(20), BigDecimal.valueOf(320),
                BigDecimal.valueOf(30000)));

        sprintService.addItems(sprint.id(),
                new AddSprintItemsRequest(List.of(first.id(), second.id())));
        return sprint;
    }

    private SprintResponse newSprintWithWork(UUID projectId) {
        StoryResponse first = newStory(projectId, "Committed A", "Detail.", "One\nTwo",
                BigDecimal.valueOf(5));
        StoryResponse second = newStory(projectId, "Committed B", "Detail.", "One",
                BigDecimal.valueOf(8));

        SprintResponse sprint = sprintService.create(new CreateSprintRequest(projectId,
                "Sprint 1", "Ship the login flow", LocalDate.now(), LocalDate.now().plusDays(13),
                (short) 5, BigDecimal.valueOf(20), BigDecimal.valueOf(320),
                BigDecimal.valueOf(30000)));

        sprintService.addItems(sprint.id(),
                new AddSprintItemsRequest(List.of(first.id(), second.id())));
        return sprint;
    }
}
