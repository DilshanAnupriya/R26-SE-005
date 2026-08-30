package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustment;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustmentRepository;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.RiskAdjustmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * A logged requirement change the team has since planned around.
 *
 * <p>The register charges a sprint the moment a requirement moves, and that charge
 * stands: no feature vector carries a logged change, so no re-assessment can absorb it.
 * Which is right, and left the panel with one entry a team could see, could not answer,
 * and watched sit on the score for the rest of the sprint. Every other finding on that
 * screen can be acted on — a blocked task clears, a claimed step is ticked — and the one
 * that could not was the one people had gone to the trouble of writing down.</p>
 *
 * <p>Ticking says something narrower than "this is fixed", because a requirement change
 * cannot be unmade. It says the plan was adjusted around it: something was moved out, or
 * re-negotiated, and the disruption has been taken. That is a claim about the sprint the
 * models genuinely cannot read, so it is recorded exactly as a claimed remediation step
 * is — a standing negative entry in the same ledger, on the same audit trail, reversible
 * by unticking.</p>
 *
 * <p>Deliberately narrow. It refuses anything that is not a logged register change, and
 * anything that lowered the risk: a board reading withdraws itself when the board
 * changes, a claimed step is unticked from its own row, and there is nothing to plan
 * around in a change that made the sprint easier.</p>
 */
@Service
@RequiredArgsConstructor
public class SprintRiskAbsorptionService {

    private final SprintRiskAdjustmentRepository adjustments;
    private final SprintRiskAdjustmentService adjustmentService;

    /** Prefix on a register charge, so it is recognisable as one rather than as board state. */
    public static final String REGISTER_PREFIX = "REQ_CHANGE";

    /** Prefix on the credit that answers one, so the pair is legible on the audit trail. */
    public static final String ABSORBED_PREFIX = "ABSORBED";

    /** How an absorption reads in the ledger, and how its subject is recovered from it. */
    private static final String REASON_PREFIX = "Re-planned for: ";

    /**
     * What planning around a logged change returns, as a fraction of what it cost.
     *
     * <p>Not a number invented here. The register already prices the reversal of a change
     * against the change itself: {@code SCOPE_REMOVED} credits 5 where
     * {@code SCOPE_ADDED} charges 8. Absorbing is the same shape of claim — the plan was
     * adjusted to take it — so it is worth the same fraction, and an absorbed scope
     * addition nets to what the register would have recorded had the scope simply been
     * taken back out.</p>
     *
     * <p><b>Deliberately not the whole charge.</b> The change still happened. A
     * requirement that moved mid-sprint is evidence of an unstable requirement whether or
     * not the team coped with it, and that is the thing this model is predicting. A tick
     * that zeroed the entry would also let anyone log a change and clear it in the same
     * minute, which would make the register a way of moving the score rather than a
     * record of what moved it.</p>
     */
    static final BigDecimal ABSORBED_FRACTION = BigDecimal.valueOf(0.625);

    /** The ledger key a register charge is written under. */
    public static String registerKey(UUID changeId) {
        return REGISTER_PREFIX + ":" + changeId;
    }

    /** The ledger key the credit for that charge is written under. */
    public static String absorbedKey(String chargeKey) {
        return ABSORBED_PREFIX + ":" + chargeKey;
    }

    /**
     * Claims, or withdraws the claim, that the sprint has been re-planned around one
     * logged change. Returns the whole ledger as it now stands.
     *
     * <p>The credit is the server's to decide — the request names the entry and nothing
     * else. A client that could name its own points could set any risk score it liked and
     * the ledger would record it as evidence.</p>
     */
    @Transactional
    public List<RiskAdjustmentResponse> absorb(UUID sprintId, UUID adjustmentId, boolean absorbed) {
        SprintRiskAdjustment charge = adjustments.findById(adjustmentId)
                .filter(entry -> entry.getSprintId().equals(sprintId))
                .orElseThrow(() -> new ResourceNotFoundException("SprintRiskAdjustment", adjustmentId));
        requireAbsorbable(charge);

        String key = absorbedKey(charge.getSourceKey());
        if (absorbed) {
            adjustmentService.restateStanding(sprintId, charge.getRiskType(), key,
                    reliefFor(charge), REASON_PREFIX + charge.getReason());
        } else {
            adjustmentService.clear(sprintId, key);
        }
        return adjustmentService.ledgerFor(sprintId).entries().stream()
                .map(RiskAdjustmentResponse::from).toList();
    }

    /**
     * The credit an absorbed charge stands for: negative, and never the whole charge.
     *
     * <p>Five-eighths of what it cost, bounded so at least one point always stays
     * standing. The bound only bites at the small end — a description update charges 2
     * and five-eighths of that is 1.25 — but that is exactly where it matters, because
     * the smallest kind of change is the one a rounding decision could otherwise clear
     * outright.</p>
     */
    static BigDecimal reliefFor(SprintRiskAdjustment charge) {
        BigDecimal relief = charge.getDelta().abs().multiply(ABSORBED_FRACTION)
                // Whole points. The panel, the toast and the ledger card all render this
                // number, and only one of them can round it without the three disagreeing.
                .setScale(0, RoundingMode.HALF_UP)
                .min(charge.getDelta().abs().subtract(BigDecimal.ONE))
                .max(BigDecimal.ZERO);
        return relief.negate();
    }

    private void requireAbsorbable(SprintRiskAdjustment charge) {
        if (!charge.isSurvivesScoring() || charge.getSourceKey() == null
                || !charge.getSourceKey().startsWith(REGISTER_PREFIX + ":")) {
            throw new BusinessRuleException(
                    "Only a logged requirement change can be planned around. A board reading "
                    + "withdraws itself when the board changes, and a claimed step is unticked "
                    + "from its own row.");
        }
        if (charge.getDelta().signum() <= 0) {
            throw new BusinessRuleException(
                    "This change lowered the sprint's risk, so there is nothing to plan around.");
        }
    }
}
