package com.sprintiq.pms.modules.ai.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plain-English half stays in step with the computed half.
 *
 * <p>Guidance is optional by design — a step without it still works, and the panel falls
 * back to the model's own sentence. That tolerance is exactly why it needs a test: a
 * lever added later would silently ship an instruction with no method attached, and
 * nothing at runtime would complain.</p>
 */
@DisplayName("Remediation guidance")
class RiskStepGuidanceTest {

    @Test
    @DisplayName("every lever the search can offer has a method written for it")
    void everyLeverIsCovered() {
        List<String> uncovered = RiskRecourseLevers.all().stream()
                .map(RiskRecourseLevers.Lever::feature)
                .filter(feature -> RiskStepGuidance.forFeature(feature) == null)
                .toList();

        assertThat(uncovered)
                .as("levers with no plain-English method — add one to RiskStepGuidance")
                .isEmpty();
    }

    @Test
    @DisplayName("no guidance is written for a reading no step can name")
    void noStrandedGuidance() {
        List<String> levers = RiskRecourseLevers.all().stream()
                .map(RiskRecourseLevers.Lever::feature).toList();

        // Text for a feature that is not a lever can never be shown, so it is either a
        // typo in the key or a lever somebody meant to add and did not.
        assertThat(RiskStepGuidance.covered()).allSatisfy(feature ->
                assertThat(levers).as("guidance for a non-lever: %s", feature).contains(feature));
    }

    @Test
    @DisplayName("reads as instructions rather than as labels")
    void readsAsProse() {
        for (String feature : RiskStepGuidance.covered()) {
            String guidance = RiskStepGuidance.forFeature(feature);
            assertThat(guidance).as(feature)
                    // Long enough to be a method and not a restatement of the title, and
                    // punctuated, because this is the one field a person reads as English.
                    .hasSizeGreaterThan(80)
                    .endsWith(".");
            assertThat(guidance).as("%s should not contain the machine name", feature)
                    .doesNotContain("_");
        }
    }
}
