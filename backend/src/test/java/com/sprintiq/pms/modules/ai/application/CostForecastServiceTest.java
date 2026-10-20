package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.modules.ai.application.port.DeveloperFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.EffortFeatureSource;
import com.sprintiq.pms.modules.ai.application.port.ProjectContextSource;
import com.sprintiq.pms.modules.ai.application.port.SprintFeatureSource;
import com.sprintiq.pms.modules.ai.domain.CostForecastRepository;
import com.sprintiq.pms.modules.ai.domain.CostStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostForecastServiceTest {

    private static final UUID SPRINT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID NADIA = UUID.randomUUID();

    @Mock private AiInferenceService inference;
    @Mock private SprintFeatureSource sprints;
    @Mock private ProjectContextSource projects;
    @Mock private DeveloperFeatureSource developers;
    @Mock private EffortFeatureSource effort;
    @Mock private CostForecastRepository forecasts;
    @Mock private SprintFeatureSource.SprintFeatures sprint;
    @Mock private DeveloperFeatureSource.DeveloperFeatures owner;
    @Mock private DeveloperFeatureSource.DeveloperFeatures nadia;
    @InjectMocks private CostForecastService service;

    @Test
    void emptySprintCannotSaveAZeroForecast() {
        when(sprints.featuresFor(SPRINT)).thenReturn(sprint);
        when(effort.sprintTaskCount(SPRINT)).thenReturn(0L);

        assertThatThrownBy(() -> service.forecast(SPRINT, CostStage.INITIAL))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Add at least one estimated task");
        verifyNoInteractions(inference, forecasts);
    }

    @Test
    void oneMissingEstimateBlocksAnOtherwiseEstimatedSprint() {
        when(sprints.featuresFor(SPRINT)).thenReturn(sprint);
        when(effort.sprintTaskCount(SPRINT)).thenReturn(2L);
        when(effort.sprintUnestimatedTaskCount(SPRINT)).thenReturn(1L);

        assertThatThrownBy(() -> service.forecast(SPRINT, CostStage.MID_SPRINT))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("1 missing or zero");
        verifyNoInteractions(inference, forecasts);
    }

    @Test
    void aZeroTotalPlanCannotBeForecast() {
        when(sprints.featuresFor(SPRINT)).thenReturn(sprint);
        when(effort.sprintTaskCount(SPRINT)).thenReturn(1L);

        assertThatThrownBy(() -> service.forecast(SPRINT, CostStage.INITIAL))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("total more than zero");
        verifyNoInteractions(inference, forecasts);
    }

    @Test
    void loggedMinutesUseExactRatesUntilFinalCurrencyRounding() {
        when(sprints.featuresFor(SPRINT)).thenReturn(sprint);
        when(sprint.projectId()).thenReturn(PROJECT);
        when(sprint.status()).thenReturn("ACTIVE");
        when(developers.averageHourlyRate(PROJECT)).thenReturn(BigDecimal.valueOf(80));
        when(developers.candidatesFor(PROJECT)).thenReturn(List.of(owner, nadia));
        when(owner.userId()).thenReturn(OWNER);
        when(owner.hourlyRate()).thenReturn(BigDecimal.valueOf(65));
        when(nadia.userId()).thenReturn(NADIA);
        when(nadia.hourlyRate()).thenReturn(BigDecimal.valueOf(95));
        when(effort.sprintEffortByUser(SPRINT)).thenReturn(List.of(
                new EffortFeatureSource.UserEffort(OWNER, 241, 0),
                new EffortFeatureSource.UserEffort(NADIA, 120, 0)));

        assertThat(service.validateSprint(SPRINT).actualCost()).isEqualByComparingTo("451.08");
    }

    @Test
    void oneMinuteOvertimeAlsoRoundsOnlyAtTheEnd() {
        when(sprints.featuresFor(SPRINT)).thenReturn(sprint);
        when(sprint.projectId()).thenReturn(PROJECT);
        when(sprint.status()).thenReturn("ACTIVE");
        when(developers.averageHourlyRate(PROJECT)).thenReturn(BigDecimal.valueOf(65));
        when(developers.candidatesFor(PROJECT)).thenReturn(List.of(owner));
        when(owner.userId()).thenReturn(OWNER);
        when(owner.hourlyRate()).thenReturn(BigDecimal.valueOf(65));
        when(effort.sprintEffortByUser(SPRINT)).thenReturn(List.of(
                new EffortFeatureSource.UserEffort(OWNER, 61, 61)));

        assertThat(service.validateSprint(SPRINT).actualCost()).isEqualByComparingTo("99.13");
    }
}
