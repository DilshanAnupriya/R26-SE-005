package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.security.AppProperties;
import com.sprintiq.pms.modules.ai.domain.CostOverrun;
import com.sprintiq.pms.modules.ai.domain.CostRiskAgreement;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.CostForecastResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.CostRiskCrossCheckResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAssessmentResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.SprintRiskResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reads the two over-budget verdicts against each other.
 *
 * <p>SprintIQ provides two readings of budget pressure. The risk model scores delivery
 * signals. The cost model estimates final labor cost from effort and rates, and its
 * mid-sprint stage also uses delivery signals. Agreement is not independent validation.</p>
 *
 * <p>The comparison makes disagreement visible without assigning a cause from either
 * prediction alone.</p>
 *
 * <p>This service only compares. It writes nothing, scores nothing, and calls no model —
 * both readings are already stored by the time it runs. The overrun it reports is
 * evaluated through the same {@link CostOverrun} the watch charges risk on, so the panel
 * and the risk score can never disagree about whether a sprint is over budget.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostRiskCrossCheckService {

    private final RiskService risk;
    private final CostForecastService costs;
    private final AppProperties properties;

    @Transactional(readOnly = true)
    public CostRiskCrossCheckResponse crossCheck(UUID sprintId) {
        SprintRiskResponse sprintRisk = risk.latest(sprintId);
        RiskAssessmentResponse overBudget = sprintRisk == null ? null : sprintRisk.overBudget();
        CostForecastResponse forecast = costs.latestWithBudget(sprintId);

        AppProperties.CostRisk config = properties.getAi().getCostRisk();
        CostOverrun overrun = forecast == null
                ? CostOverrun.evaluate(null, null, null, config.getMarginPct(),
                        config.getPctPerRiskPoint(), config.getMinDelta(), config.getMaxDelta())
                : CostOverrun.evaluate(forecast.forecastAmount(), forecast.budgetAmount(),
                        forecast.varianceVsBudgetPct(), config.getMarginPct(),
                        config.getPctPerRiskPoint(), config.getMinDelta(), config.getMaxDelta());

        boolean comparable = overrun.outcome() != CostOverrun.Outcome.NO_BUDGET
                && overrun.outcome() != CostOverrun.Outcome.NO_FORECAST;
        CostRiskAgreement agreement = CostRiskAgreement.of(
                overBudget == null ? null : overBudget.severity(),
                overrun.isCharged(), comparable);

        return new CostRiskCrossCheckResponse(sprintId, agreement,
                headline(agreement, overrun),
                detail(agreement, overrun, overBudget),
                overBudget == null ? null : overBudget.riskScore(),
                overBudget == null ? null : overBudget.severity(),
                forecast == null ? null : forecast.forecastAmount(),
                forecast == null ? null : forecast.budgetAmount(),
                forecast == null ? null : forecast.varianceVsBudgetPct(),
                forecast == null ? null : forecast.currency(),
                BigDecimal.valueOf(config.getMarginPct()),
                overrun.isCharged());
    }

    private String headline(CostRiskAgreement agreement, CostOverrun overrun) {
        return switch (agreement) {
            case BOTH_HIGH -> "Both readings indicate budget pressure.";
            case BOTH_LOW -> "Neither reading currently raises a budget alert.";
            case COST_ONLY -> "The cost forecast is over budget; the risk score is below its warning threshold.";
            case RISK_ONLY -> "The risk score is high; the cost forecast remains below budget.";
            case UNKNOWN -> unknownHeadline(overrun);
        };
    }

    private String unknownHeadline(CostOverrun overrun) {
        return switch (overrun.outcome()) {
            case NO_BUDGET -> "No budget is set for this sprint, so there is nothing to compare against.";
            case NO_FORECAST -> "No cost forecast has been run for this sprint yet.";
            default -> "The over-budget risk model has not scored this sprint yet.";
        };
    }

    private String detail(CostRiskAgreement agreement, CostOverrun overrun,
                          RiskAssessmentResponse overBudget) {
        return switch (agreement) {
            case BOTH_HIGH -> String.format(
                    "The cost forecast is %s%% over budget, and the risk model puts the "
                            + "over-budget probability at %s%%. Review actual cost, task effort, "
                            + "rates, and scope before deciding what is driving the risk.",
                    overrun.variancePct(), percent(overBudget));
            case BOTH_LOW -> bothLowDetail(overrun, overBudget);
            case COST_ONLY -> String.format(
                    "The cost forecast is %s%% over budget, while the risk model reads %s at %s%% — "
                            + "below its warning threshold. Review the budget, hourly rates, task "
                            + "effort, and recent progress before assigning a cause.",
                    overrun.variancePct(), band(overBudget), percent(overBudget));
            case RISK_ONLY -> String.format(
                    "The risk model puts the over-budget probability at %s%%, but the forecast "
                            + "is still inside budget. Review delivery signals and data freshness; "
                            + "the cost estimate may change as more work is logged.",
                    percent(overBudget));
            case UNKNOWN -> unknownDetail(overrun);
        };
    }

    /**
     * The quiet case, stated as two readings rather than as a verdict.
     *
     * <p>This is the branch most likely to be read carelessly, so it says both numbers
     * out loud. A sprint sitting at medium risk with spend a point under the cost
     * model's error margin is genuinely not being flagged — and is also not fine. The
     * text has to carry both of those or it is telling a delivery lead to stop looking.</p>
     */
    private String bothLowDetail(CostOverrun overrun, RiskAssessmentResponse overBudget) {
        String costPart = overrun.outcome() == CostOverrun.Outcome.WITHIN_MARGIN
                && overrun.variancePct() != null
                && overrun.variancePct().signum() > 0
                ? String.format("The cost forecast is %s%% over budget, which is inside the %.1f%% the cost "
                        + "model is typically wrong by, so it is not counted as an overrun.",
                        overrun.variancePct(), overrun.marginPct())
                : "The cost forecast is below budget.";
        String riskPart = overBudget == null
                ? " The risk model has not scored this sprint."
                : String.format(" The risk model reads %s at %s%%, below the level it treats as "
                        + "a warning.", band(overBudget), percent(overBudget));
        return costPart + riskPart;
    }

    /** The severity band in prose — "medium", not MEDIUM. */
    private String band(RiskAssessmentResponse assessment) {
        return assessment == null || assessment.severity() == null
                ? "unscored"
                : assessment.severity().name().toLowerCase();
    }

    private String unknownDetail(CostOverrun overrun) {
        return switch (overrun.outcome()) {
            case NO_BUDGET -> "Set a budget on the sprint, or on the project, and this "
                    + "comparison becomes available. A missing budget is not a zero one.";
            case NO_FORECAST -> "Run a cost forecast for this sprint and the two readings "
                    + "can be compared.";
            default -> "Assess the sprint's risk and the two readings can be compared.";
        };
    }

    /** The over-budget probability as a whole percentage, for a sentence rather than a chart. */
    private String percent(RiskAssessmentResponse assessment) {
        if (assessment == null || assessment.riskScore() == null) {
            return "an unknown";
        }
        return assessment.riskScore().multiply(BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
