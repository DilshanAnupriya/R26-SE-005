package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.domain.RiskType;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustment;
import com.sprintiq.pms.modules.ai.domain.SprintRiskAdjustmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Planning around a logged requirement change.
 *
 * <p>The register's charge stands: nothing the models read represents a logged change, so
 * no re-assessment can fold it away. That is correct and it left one finding on the panel
 * a team could see and could not answer. This is the answer, and the properties that stop
 * it becoming a way of moving the score rather than a record of it.</p>
 *
 * <p>Hand-written fakes rather than mocks, following {@link RiskRemediationServiceTest}:
 * the assertions want the arguments themselves, and it keeps the test off the inline mock
 * maker.</p>
 */
@DisplayName("Absorbing a logged requirement change")
class SprintRiskAbsorptionServiceTest {

    private static final UUID SPRINT = UUID.randomUUID();
    private static final UUID CHANGE = UUID.randomUUID();

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
    }

    /** A charge as {@code RequirementChangeService} writes one. */
    private static SprintRiskAdjustment charge(BigDecimal delta, String reason) {
        SprintRiskAdjustment entry = SprintRiskAdjustment.builder()
                .sprintId(SPRINT)
                .riskType(RiskType.REQUIREMENT_CHANGE)
                .delta(delta)
                .reason(reason)
                .sourceKey(SprintRiskAbsorptionService.registerKey(CHANGE))
                .survivesScoring(true)
                .build();
        entry.setCreatedAt(Instant.parse("2026-08-30T09:00:00Z"));
        return entry;
    }

    private SprintRiskAbsorptionService serviceFor(SprintRiskAdjustment entry) {
        return new SprintRiskAbsorptionService(repositoryReturning(entry), ledger);
    }

    // ------------------------------------------------------------------- credit

    @Test
    @DisplayName("credits five-eighths of the charge, the register's own reversal ratio")
    void creditsTheRegistersOwnFraction() {
        // SCOPE_ADDED charges 8 and the register prices taking that scope back out at 5.
        // Absorbing is the same shape of claim, so it is worth the same fraction — and an
        // absorbed scope addition nets to exactly what a scope removal would have left.
        SprintRiskAbsorptionService service = serviceFor(
                charge(BigDecimal.valueOf(8), "Scope added: new work after the commitment was frozen"));

        service.absorb(SPRINT, CHANGE, true);

        assertThat(ledger.written).singleElement().satisfies(entry -> {
            assertThat(entry.sourceKey())
                    .isEqualTo("ABSORBED:" + SprintRiskAbsorptionService.registerKey(CHANGE));
            assertThat(entry.delta()).isEqualByComparingTo(BigDecimal.valueOf(-5));
            assertThat(entry.riskType()).isEqualTo(RiskType.REQUIREMENT_CHANGE);
        });
    }

    @Test
    @DisplayName("a raised priority is worth three of its four points")
    void pricesAPriorityRaise() {
        SprintRiskAbsorptionService service = serviceFor(
                charge(BigDecimal.valueOf(4), "Priority raised mid-sprint: planning disruption"));

        service.absorb(SPRINT, CHANGE, true);

        assertThat(ledger.written).singleElement().satisfies(entry ->
                assertThat(entry.delta()).isEqualByComparingTo(BigDecimal.valueOf(-3)));
    }

    @Test
    @DisplayName("never clears the charge outright, however small it is")
    void alwaysLeavesAPointStanding() {
        // A description update charges 2, and five-eighths of that is 1.25 — the one place
        // rounding could otherwise decide that the smallest kind of change vanishes when
        // somebody ticks a box. The requirement still moved; that fact stays on the score.
        SprintRiskAbsorptionService service = serviceFor(
                charge(BigDecimal.valueOf(2), "Description updated: new scope detail added"));

        service.absorb(SPRINT, CHANGE, true);

        assertThat(ledger.written).singleElement().satisfies(entry ->
                assertThat(entry.delta()).isEqualByComparingTo(BigDecimal.valueOf(-1)));
    }

    @Test
    @DisplayName("names the change it answers, so the pair reads on the audit trail")
    void quotesTheChargeInItsReason() {
        SprintRiskAbsorptionService service = serviceFor(
                charge(BigDecimal.valueOf(4), "Priority raised mid-sprint: planning disruption"));

        service.absorb(SPRINT, CHANGE, true);

        assertThat(ledger.written).singleElement().satisfies(entry ->
                assertThat(entry.reason())
                        .isEqualTo("Re-planned for: Priority raised mid-sprint: planning disruption"));
    }

    @Test
    @DisplayName("stands after Assess, because no feature vector carries the claim")
    void theCreditStands() {
        SprintRiskAbsorptionService service = serviceFor(charge(BigDecimal.valueOf(4), "Priority raised"));

        service.absorb(SPRINT, CHANGE, true);

        // restateStanding, not record: a claim the model cannot read must not be folded
        // away by the next scoring, or the points come straight back and the tick reads as
        // having been undone.
        assertThat(ledger.written).singleElement()
                .satisfies(entry -> assertThat(entry.standing()).isTrue());
    }

    // ------------------------------------------------------------------ undoing

    @Test
    @DisplayName("unticking withdraws the credit and writes nothing new")
    void unticking() {
        SprintRiskAbsorptionService service = serviceFor(charge(BigDecimal.valueOf(4), "Priority raised"));

        service.absorb(SPRINT, CHANGE, false);

        assertThat(ledger.written).isEmpty();
        assertThat(ledger.cleared)
                .containsExactly("ABSORBED:" + SprintRiskAbsorptionService.registerKey(CHANGE));
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("refuses a board reading, which withdraws itself when the board changes")
    void refusesBoardState() {
        SprintRiskAdjustment blocked = SprintRiskAdjustment.builder()
                .sprintId(SPRINT).riskType(RiskType.OVER_BUDGET)
                .delta(BigDecimal.valueOf(8)).reason("Task blocked: PAY-14")
                .sourceKey("TASK_OVERDUE:" + UUID.randomUUID()).survivesScoring(false)
                .build();

        assertThatThrownBy(() -> serviceFor(blocked).absorb(SPRINT, CHANGE, true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only a logged requirement change");
    }

    @Test
    @DisplayName("refuses a claimed remediation step, which is unticked from its own row")
    void refusesAClaimedStep() {
        SprintRiskAdjustment claim = SprintRiskAdjustment.builder()
                .sprintId(SPRINT).riskType(RiskType.OVER_BUDGET)
                .delta(BigDecimal.valueOf(-12)).reason("Fix applied: Reduce the commitment")
                .sourceKey("STEP:OVER_BUDGET:committed_story_points").survivesScoring(true)
                .build();

        assertThatThrownBy(() -> serviceFor(claim).absorb(SPRINT, CHANGE, true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only a logged requirement change");
    }

    @Test
    @DisplayName("refuses a change that lowered the risk — there is nothing to plan around")
    void refusesACredit() {
        SprintRiskAbsorptionService service = serviceFor(
                charge(BigDecimal.valueOf(-5), "Scope removed: less to deliver than was committed"));

        assertThatThrownBy(() -> service.absorb(SPRINT, CHANGE, true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("nothing to plan around");
    }

    @Test
    @DisplayName("refuses an entry belonging to another sprint")
    void refusesAnotherSprintsEntry() {
        SprintRiskAbsorptionService service = serviceFor(charge(BigDecimal.valueOf(4), "Priority raised"));

        assertThatThrownBy(() -> service.absorb(UUID.randomUUID(), CHANGE, true))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --------------------------------------------------------------- the fakes

    private record Entry(RiskType riskType, String sourceKey, BigDecimal delta, String reason,
                         boolean standing) {}

    private static final class Ledger extends SprintRiskAdjustmentService {

        private final List<Entry> written = new ArrayList<>();
        private final List<String> cleared = new ArrayList<>();

        Ledger() {
            // The repository is never reached: every method the service calls is
            // overridden here.
            super(null);
        }

        @Override
        public void restateStanding(UUID sprintId, RiskType riskType, String sourceKey,
                                    BigDecimal delta, String reason) {
            written.add(new Entry(riskType, sourceKey, delta, reason, true));
        }

        @Override
        public void clear(UUID sprintId, String sourceKey) {
            cleared.add(sourceKey);
        }

        @Override
        public SprintRiskAdjustmentService.Ledger ledgerFor(UUID sprintId) {
            return new SprintRiskAdjustmentService.Ledger(List.of());
        }
    }

    private static SprintRiskAdjustmentRepository repositoryReturning(SprintRiskAdjustment entry) {
        return (SprintRiskAdjustmentRepository) Proxy.newProxyInstance(
                SprintRiskAdjustmentRepository.class.getClassLoader(),
                new Class<?>[]{SprintRiskAdjustmentRepository.class},
                (proxy, method, args) -> {
                    if (!"findById".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return Optional.ofNullable(entry);
                });
    }
}
