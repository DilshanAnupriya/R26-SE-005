package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.CostRiskAgreement;
import com.sprintiq.pms.modules.ai.domain.CostStage;
import com.sprintiq.pms.modules.ai.domain.RiskSeverity;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.CostForecastResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.CostRiskCrossCheckResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAssessmentResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.SprintRiskResponse;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Two models, read against each other.
 *
 * <p>The comparison reports both readings without treating agreement as independent
 * validation or diagnosing the cause of a disagreement.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Cost/risk cross-check")
class CostRiskCrossCheckServiceTest {

    private static final UUID SPRINT = UUID.randomUUID();

    @Mock private RiskService risk;
    @Mock private CostForecastService costs;

    private CostRiskCrossCheckService service() {
        return new CostRiskCrossCheckService(risk, costs, new AppProperties());
    }

    private void givenRisk(RiskSeverity severity, double score) {
        RiskAssessmentResponse overBudget = new RiskAssessmentResponse(
                UUID.randomUUID(), SPRINT, RiskType.OVER_BUDGET, BigDecimal.valueOf(score),
                score >= 0.5, severity, BigDecimal.valueOf(0.8), List.of(), List.of(),
                "because", UUID.randomUUID(), null, null, Instant.now(),
                BigDecimal.ZERO, BigDecimal.valueOf(score), severity);
        when(risk.latest(SPRINT)).thenReturn(new SprintRiskResponse(
                SPRINT, overBudget, null, null, severity, null));
    }

    private void givenNoRisk() {
        when(risk.latest(SPRINT)).thenReturn(
                new SprintRiskResponse(SPRINT, null, null, null, null, null));
    }

    private void givenForecast(Double amount, Double budget, Double variancePct) {
        when(costs.latestWithBudget(SPRINT)).thenReturn(new CostForecastResponse(
                UUID.randomUUID(), SPRINT, CostStage.INITIAL,
                amount == null ? null : BigDecimal.valueOf(amount),
                BigDecimal.valueOf(500), BigDecimal.valueOf(100), BigDecimal.valueOf(900),
                "USD", BigDecimal.valueOf(0.7), "RISING",
                variancePct == null ? null : BigDecimal.valueOf(variancePct),
                budget == null ? null : BigDecimal.valueOf(budget),
                List.of(), "because", UUID.randomUUID(), Instant.now()));
    }

    @Test
    @DisplayName("reports both readings without claiming independent validation")
    void bothHigh() {
        givenRisk(RiskSeverity.HIGH, 0.72);
        givenForecast(4000.0, 2500.0, 60.0);

        CostRiskCrossCheckResponse response = service().crossCheck(SPRINT);

        assertThat(response.agreement()).isEqualTo(CostRiskAgreement.BOTH_HIGH);
        assertThat(response.costCharged()).isTrue();
        assertThat(response.detail()).contains("Review actual cost");
        assertThat(response.detail()).doesNotContain("share no inputs");
    }

    @Test
    @DisplayName("cost-only warning does not assign a cause")
    void costOnlyPointsAtThePlan() {
        givenRisk(RiskSeverity.LOW, 0.2);
        givenForecast(4000.0, 2500.0, 60.0);

        CostRiskCrossCheckResponse response = service().crossCheck(SPRINT);

        assertThat(response.agreement()).isEqualTo(CostRiskAgreement.COST_ONLY);
        assertThat(response.detail()).contains("Review the budget");
        assertThat(response.detail()).contains("before assigning a cause");
    }

    @Test
    @DisplayName("risk-only warning does not diagnose execution")
    void riskOnlyPointsAtTheTeam() {
        givenRisk(RiskSeverity.HIGH, 0.8);
        givenForecast(2000.0, 2500.0, -20.0);

        CostRiskCrossCheckResponse response = service().crossCheck(SPRINT);

        assertThat(response.agreement()).isEqualTo(CostRiskAgreement.RISK_ONLY);
        assertThat(response.detail()).contains("Review delivery signals and data freshness");
    }

    @Test
    @DisplayName("an overrun inside the cost model's error margin is not a disagreement")
    void marginIsNotAnOverrun() {
        givenRisk(RiskSeverity.LOW, 0.2);
        // Sprint 24's real reading: 22.37% over, against a model wrong by 23.5% on average.
        givenForecast(3059.33, 2500.0, 22.37);

        CostRiskCrossCheckResponse response = service().crossCheck(SPRINT);

        assertThat(response.agreement()).isEqualTo(CostRiskAgreement.BOTH_LOW);
        assertThat(response.costCharged()).isFalse();
        // Still reported, so the panel can show the figure and say why it does not count.
        assertThat(response.variancePct()).isEqualByComparingTo(BigDecimal.valueOf(22.37));
        assertThat(response.marginPct()).isEqualByComparingTo(BigDecimal.valueOf(23.5));
        // And says so, rather than rounding an in-margin overrun down to "on track".
        assertThat(response.detail()).contains("cost forecast is 22.37% over budget").contains("not counted");
    }

    @Test
    @DisplayName("no budget means no comparison, not a passing grade")
    void missingBudgetIsUnknown() {
        givenRisk(RiskSeverity.HIGH, 0.8);
        givenForecast(4000.0, null, null);

        CostRiskCrossCheckResponse response = service().crossCheck(SPRINT);

        assertThat(response.agreement()).isEqualTo(CostRiskAgreement.UNKNOWN);
        assertThat(response.detail()).contains("A missing budget is not a zero one");
    }

    @Test
    @DisplayName("no forecast means no comparison either")
    void missingForecastIsUnknown() {
        givenRisk(RiskSeverity.HIGH, 0.8);
        when(costs.latestWithBudget(SPRINT)).thenReturn(null);

        CostRiskCrossCheckResponse response = service().crossCheck(SPRINT);

        assertThat(response.agreement()).isEqualTo(CostRiskAgreement.UNKNOWN);
        assertThat(response.headline()).contains("No cost forecast");
    }

    @Test
    @DisplayName("an unscored sprint is unknown even when the money is clearly over")
    void unscoredRiskIsUnknown() {
        givenNoRisk();
        givenForecast(4000.0, 2500.0, 60.0);

        CostRiskCrossCheckResponse response = service().crossCheck(SPRINT);

        assertThat(response.agreement()).isEqualTo(CostRiskAgreement.UNKNOWN);
        assertThat(response.riskScore()).isNull();
    }

    @Test
    @DisplayName("compares stored readings — it never runs a model")
    void neverRunsAModel() {
        givenRisk(RiskSeverity.HIGH, 0.8);
        givenForecast(4000.0, 2500.0, 60.0);

        service().crossCheck(SPRINT);

        // latest() and latestWithBudget() are both reads. Nothing here should score, and
        // a panel that billed for two inferences every time it rendered would be turned
        // off within a week.
        org.mockito.Mockito.verify(risk, org.mockito.Mockito.never()).assess(SPRINT);
        org.mockito.Mockito.verify(costs, org.mockito.Mockito.never())
                .forecast(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a medium reading is not reported as on track")
    void mediumIsNotOnTrack() {
        // 0.463 is Sprint 24's real over-budget score: medium, under the 0.60 the system
        // treats as a warning, and nowhere near healthy. The panel must not round that
        // to reassurance — a delivery lead who stops looking here is the failure mode.
        givenRisk(RiskSeverity.MEDIUM, 0.463);
        givenForecast(3059.33, 2500.0, 22.37);

        CostRiskCrossCheckResponse response = service().crossCheck(SPRINT);

        assertThat(response.agreement()).isEqualTo(CostRiskAgreement.BOTH_LOW);
        assertThat(response.headline()).doesNotContain("on track");
        assertThat(response.headline()).contains("Neither reading currently raises a budget alert");
        assertThat(response.detail()).contains("medium at 46%");
    }
}
