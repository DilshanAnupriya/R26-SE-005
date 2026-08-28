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
 * <p>SprintIQ predicts a sprint going over budget twice, by routes that share no inputs.
 * {@code M2_OVER_BUDGET} reads behaviour — velocity, carry-over, rework, blocked time —
 * and never sees money. {@code M4_COST_*} reads effort hours against a blended rate and
 * never sees how the team is coping. Neither is told the other's answer, and that is
 * deliberate: feeding cost into the risk features would need a retrain and would risk
 * leaking the outcome into its own predictor.</p>
 *
 * <p>Which leaves the comparison unmade, and the comparison is where the useful part is.
 * When two independent readings agree, that is corroboration no single model can offer.
 * When they disagree, the disagreement names the problem: money over but team fine is a
 * pricing failure, team struggling but money fine is a process one. A delivery lead
 * acting on either number alone cannot tell those apart.</p>
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
            case BOTH_HIGH -> "Both models say this sprint is heading over budget.";
            // Not "on track". Neither model flagging is a weaker claim than the sprint
            // being healthy — a medium reading and a variance just inside the cost
            // model's error both land here, and calling either of those "on track"
            // would be the panel overstating what it actually knows.
            case BOTH_LOW -> "Neither model is flagging this sprint.";
            case COST_ONLY -> "The forecast is over budget, but the risk model is not flagging the team.";
            case RISK_ONLY -> "The team is flagged, but the money is still within budget.";
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
            // Two readings with no inputs in common landing on the same answer is the
            // strongest evidence this system can produce. Saying so is the point of the
            // panel — the same conclusion from one model is a guess.
            case BOTH_HIGH -> String.format(
                    "The cost forecast is %s%% over budget, and the risk model puts the "
                            + "over-budget probability at %s%%. These two models share no inputs — "
                            + "one reads spend, the other reads how the team is working — so agreeing "
                            + "is real corroboration rather than the same signal counted twice. "
                            + "Treat the overrun as likely and act on scope.",
                    overrun.variancePct(), percent(overBudget));
            case BOTH_LOW -> bothLowDetail(overrun, overBudget);
            case COST_ONLY -> String.format(
                    "Spend is %s%% over budget, while the risk model reads %s at %s%% — below "
                            + "the level it treats as a warning. That pattern usually means the "
                            + "sprint was priced too low rather than executed badly. Re-check the "
                            + "budget and the assumed rate before chasing the team; pushing harder "
                            + "will not close a gap that opened at planning.",
                    overrun.variancePct(), band(overBudget), percent(overBudget));
            case RISK_ONLY -> String.format(
                    "The risk model puts the over-budget probability at %s%%, but the forecast "
                            + "is still inside budget. The work is going badly and happens to be "
                            + "cheap. Re-budgeting will not fix this — look at what is blocked, "
                            + "reopened or waiting.",
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
                ? String.format("Spend is %s%% over budget, which is inside the %.1f%% the cost "
                        + "model is typically wrong by, so it is not counted as an overrun.",
                        overrun.variancePct(), overrun.marginPct())
                : "Spend is tracking to plan.";
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
