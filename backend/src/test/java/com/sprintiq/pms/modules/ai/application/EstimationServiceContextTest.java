package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.modules.ai.application.port.DeveloperFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource;
import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.AiPrediction;
import com.sprintiq.pms.modules.ai.domain.PredictionStatus;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.SizingContext;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.StoryPointEstimateResponse;
import com.sprintiq.pms.security.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What the estimate says it was scored against.
 *
 * <p>Team size, experience, domain and tech stack have gone to the model since it
 * shipped and none of them were ever reported back, so a panel whose drivers are all
 * about the story's own words read as though the team were not part of the estimate.
 * These tests pin the two things that made the fix worth doing: the context is read
 * from the model service's own account of the row it scored rather than from which of
 * the project's fields happen to be null, and a call that produced no such account
 * reports nothing rather than describing a scoring that never happened.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Story-point sizing context")
class EstimationServiceContextTest {

    private static final UUID STORY = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();

    @Mock private AiInferenceService inference;
    @Mock private StoryFeatureSource stories;
    @Mock private SprintFeatureSource sprints;
    @Mock private ProjectContextSource projects;
    @Mock private DeveloperFeatureSource developers;

    private EstimationService service() {
        return new EstimationService(inference, stories, sprints, projects, developers,
                new AppProperties());
    }

    /** A project with every model input filled in. */
    private void givenProject(String techStack) {
        when(projects.contextFor(PROJECT)).thenReturn(new ProjectContextSource.ProjectContext(
                PROJECT, UUID.randomUUID(), "Payments", "PAY", "ACTIVE",
                BigDecimal.valueOf(100_000), "USD", 12, 3, 6,
                "ecommerce", techStack, (short) 6, "mid",
                BigDecimal.valueOf(22), BigDecimal.valueOf(0.84), (short) 14));
    }

    private void givenStory() {
        when(stories.featuresFor(STORY)).thenReturn(new StoryFeatureSource.StoryFeatures(
                STORY, PROJECT, "PAY-7", "FX rate provider integration", "A description.",
                "Given a rate, when it changes, then it is stored", 1, "STORY",
                Priority.MEDIUM, 2, 0, 3, false, null,
                BigDecimal.valueOf(5), BigDecimal.valueOf(5), 9, Instant.now(),
                List.of("payments")));
    }

    private void givenPrediction(Map<String, Object> rawResponse) {
        when(inference.infer(any())).thenReturn(AiPrediction.builder()
                .modelCode(AiModelCode.M1_STORY_POINTS)
                .modelVersion("2.0.0")
                .predictedValue(BigDecimal.valueOf(8))
                .confidence(BigDecimal.valueOf(0.53))
                .status(PredictionStatus.SUCCESS)
                .rawResponse(rawResponse)
                .build());
    }

    /** A response as the model service actually shapes it. */
    private static Map<String, Object> responseWith(List<String> defaulted) {
        return Map.of(
                "value", 8,
                "raw", Map.of("point", 6.67),
                "provenance", Map.of(
                        "defaulted_to_training_median", defaulted,
                        "inferred_from_text", List.of("has_security"),
                        "text_terms_recognised", 12));
    }

    @Test
    @DisplayName("reports the project context the model was given")
    void reportsProjectContext() {
        givenProject("react");
        givenStory();
        givenPrediction(responseWith(List.of()));

        SizingContext context = service().estimate(STORY).context();

        assertThat(context).isNotNull();
        assertThat(context.teamSize()).isEqualTo((short) 6);
        assertThat(context.devExperienceLevel()).isEqualTo("mid");
        assertThat(context.domain()).isEqualTo("ecommerce");
        assertThat(context.techStack()).isEqualTo("react");
        assertThat(context.defaulted()).isEmpty();
        assertThat(context.inferredFromText()).containsExactly("has_security");
        assertThat(context.textTermsRecognised()).isEqualTo(12);
    }

    @Test
    @DisplayName("trusts the model's account of what was missing over the project's own nulls")
    void trustsTheModelAboutWhatWasMissing() {
        // The case that makes this worth doing. The model one-hot encodes ten tech
        // stacks and Laravel is not one of them, so the column is never set and the
        // story scores as though the field were blank. Reading the project's nulls
        // would report this project as fully specified.
        givenProject("laravel");
        givenStory();
        givenPrediction(responseWith(List.of("tech_stack")));

        SizingContext context = service().estimate(STORY).context();

        assertThat(context.techStack()).isEqualTo("laravel");
        assertThat(context.defaulted()).containsExactly("tech_stack");
    }

    @Test
    @DisplayName("says nothing when the call produced no account of what it scored")
    void staysSilentWithoutProvenance() {
        // A failed or timed-out call, or the mock provider, which reads none of these
        // inputs. Captioning that estimate with the project's fields would claim a
        // scoring that never happened.
        givenProject("react");
        givenStory();
        givenPrediction(Map.of("value", 8));

        StoryPointEstimateResponse estimate = service().estimate(STORY);

        assertThat(estimate.suggestedPoints()).isEqualByComparingTo("8");
        assertThat(estimate.context()).isNull();
    }

    @Test
    @DisplayName("carries the model's pre-snap number so an 8 can be read as a low one")
    void carriesTheRawNumber() {
        givenProject("react");
        givenStory();
        givenPrediction(responseWith(List.of()));

        assertThat(service().estimate(STORY).prediction().rawValue())
                .isEqualByComparingTo("6.67");
    }
}
