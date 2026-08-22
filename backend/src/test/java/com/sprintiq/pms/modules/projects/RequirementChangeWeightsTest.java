package com.sprintiq.pms.modules.projects;

import com.sprintiq.pms.modules.projects.domain.ChangeImpactDirection;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Requirement change weights")
class RequirementChangeWeightsTest {

    @Test
    @DisplayName("scope changes ignore the stated direction")
    void scopeChangesAreDirectionFixed() {
        assertThat(RequirementChangeType.SCOPE_ADDED.isDirectionFixed()).isTrue();
        assertThat(RequirementChangeType.SCOPE_REMOVED.isDirectionFixed()).isTrue();

        // Somebody logging "we added scope, but it made things easier" does not get to
        // credit the sprint for it.
        assertThat(RequirementChangeType.SCOPE_ADDED.riskDelta(ChangeImpactDirection.DECREASE))
                .isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(RequirementChangeType.SCOPE_REMOVED.riskDelta(ChangeImpactDirection.INCREASE))
                .isEqualByComparingTo(BigDecimal.valueOf(-5));
    }

    @Test
    @DisplayName("a fixed type reports the direction it actually applied")
    void fixedTypesReportTheirRealDirection() {
        assertThat(RequirementChangeType.SCOPE_ADDED.effectiveDirection(ChangeImpactDirection.DECREASE))
                .isEqualTo(ChangeImpactDirection.INCREASE);
        assertThat(RequirementChangeType.SCOPE_REMOVED.effectiveDirection(ChangeImpactDirection.INCREASE))
                .isEqualTo(ChangeImpactDirection.DECREASE);
    }

    @ParameterizedTest
    @EnumSource(value = RequirementChangeType.class,
            names = {"ACCEPTANCE_CRITERIA_CHANGED", "PRIORITY_CHANGED", "DESCRIPTION_UPDATED", "OTHER"})
    @DisplayName("the other four cost points one way and credit them the other")
    void flexibleTypesGoBothWays(RequirementChangeType type) {
        assertThat(type.isDirectionFixed()).isFalse();
        assertThat(type.riskDelta(ChangeImpactDirection.INCREASE)).isPositive();
        assertThat(type.riskDelta(ChangeImpactDirection.DECREASE)).isNegative();
    }

    @ParameterizedTest
    @EnumSource(RequirementChangeType.class)
    @DisplayName("a null direction is read as INCREASE, never as free")
    void nullDirectionIsNotFree(RequirementChangeType type) {
        assertThat(type.riskDelta(null))
                .isEqualByComparingTo(type.riskDelta(ChangeImpactDirection.INCREASE));
        assertThat(type.effectiveDirection(null)).isNotNull();
    }

    @Test
    @DisplayName("added scope outweighs every other kind of change")
    void addedScopeIsTheWorstCase() {
        BigDecimal added = RequirementChangeType.SCOPE_ADDED.riskDelta(ChangeImpactDirection.INCREASE);
        for (RequirementChangeType type : RequirementChangeType.values()) {
            assertThat(added)
                    .as("%s should not cost more than admitting new scope", type)
                    .isGreaterThanOrEqualTo(type.riskDelta(ChangeImpactDirection.INCREASE));
        }
    }

    @ParameterizedTest
    @EnumSource(RequirementChangeType.class)
    @DisplayName("every type and direction has a sentence for the ledger")
    void everyCombinationIsExplained(RequirementChangeType type) {
        assertThat(type.reason(ChangeImpactDirection.INCREASE)).isNotBlank();
        assertThat(type.reason(ChangeImpactDirection.DECREASE)).isNotBlank();
    }
}
