package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.AiPrediction;
import com.sprintiq.pms.modules.ai.domain.RiskSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a model's answer becomes the verdict a dashboard shows.
 *
 * <p>Written because of a bug that produced no error and no wrong-looking output. The
 * "at risk" flag was read from {@code "AT_RISK".equals(predictedLabel)} — the mock's
 * vocabulary. The CatBoost models answer with Low/Medium/High, so once they were wired
 * in, every sprint reported {@code atRisk=false} while the severity badge beside it read
 * HIGH. Nothing failed; the card just quietly contradicted itself.</p>
 *
 * <p>Both models are kept working here: the mock still says it in words, and the real
 * models say it with a probability.</p>
 */
@DisplayName("Risk verdict")
class RiskVerdictTest {

    /** The service under test needs no collaborators for these two decisions. */
    private final RiskService service = new RiskService(null, null, null, null, null, null, null);

    private static AiPrediction withLabel(String label) {
        return AiPrediction.builder().predictedLabel(label).build();
    }

    private static AiPrediction unlabelled() {
        return AiPrediction.builder().build();
    }

    @Nested
    @DisplayName("at-risk flag")
    class AtRisk {

        @Test
        @DisplayName("honours the mock's word, whatever the score says")
        void mockVocabularyStillWorks() {
            assertThat(service.atRisk(withLabel("AT_RISK"), BigDecimal.valueOf(0.1))).isTrue();
        }

        @Test
        @DisplayName("reads a real model's probability, which never says AT_RISK")
        void catBoostProbabilityIsRead() {
            // The regression. These labels are what the CatBoost service actually
            // returns; before the fix every one of them produced atRisk=false.
            assertThat(service.atRisk(withLabel("HIGH"), BigDecimal.valueOf(0.68))).isTrue();
            assertThat(service.atRisk(withLabel("MEDIUM"), BigDecimal.valueOf(0.48))).isFalse();
            assertThat(service.atRisk(withLabel("LOW"), BigDecimal.valueOf(0.02))).isFalse();
        }

        @Test
        @DisplayName("flags at exactly the binary head's own threshold")
        void thresholdMatchesTheModelsOwnDecisionBoundary() {
            // 0.5 is where CatBoost's predict() splits, so this is the model's call
            // being read rather than a second threshold invented in the backend.
            assertThat(service.atRisk(unlabelled(), BigDecimal.valueOf(0.50))).isTrue();
            assertThat(service.atRisk(unlabelled(), BigDecimal.valueOf(0.4999))).isFalse();
        }

        @Test
        @DisplayName("a missing score is not a risk")
        void nullScoreIsNotFlagged() {
            assertThat(service.atRisk(unlabelled(), null)).isFalse();
        }
    }

    @Nested
    @DisplayName("severity bands")
    class Severity {

        @Test
        @DisplayName("maps the probability onto the four bands a dashboard colours")
        void bands() {
            assertThat(service.severityOf(BigDecimal.valueOf(0.85))).isEqualTo(RiskSeverity.CRITICAL);
            assertThat(service.severityOf(BigDecimal.valueOf(0.68))).isEqualTo(RiskSeverity.HIGH);
            assertThat(service.severityOf(BigDecimal.valueOf(0.48))).isEqualTo(RiskSeverity.MEDIUM);
            assertThat(service.severityOf(BigDecimal.valueOf(0.02))).isEqualTo(RiskSeverity.LOW);
        }

        @Test
        @DisplayName("a HIGH or CRITICAL sprint is always flagged")
        void severeSprintsNeverShowAReassuringIcon() {
            // This is the contradiction the bug actually produced: the card picks its
            // icon from atRisk and its badge from severity, so a HIGH band beside a
            // shield read as "severe, and fine". The reverse pairing — flagged at
            // MEDIUM — is not a contradiction but a warning at middling severity,
            // which is exactly what a 0.55 probability is.
            for (double score : new double[]{0.60, 0.68, 0.75, 0.80, 0.95}) {
                BigDecimal value = BigDecimal.valueOf(score);
                assertThat(service.severityOf(value)).isIn(RiskSeverity.HIGH, RiskSeverity.CRITICAL);
                assertThat(service.atRisk(unlabelled(), value))
                        .as("score %s bands as severe, so it must also be flagged", value)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the flag threshold sits below the HIGH band, never above it")
        void thresholdsCannotDriftIntoContradiction() {
            // Stated as a relationship rather than two constants: if someone later
            // raises the at-risk threshold past 0.60, severe sprints stop being
            // flagged and the original bug returns in a new form.
            BigDecimal justBelowHigh = BigDecimal.valueOf(0.599);
            assertThat(service.severityOf(justBelowHigh)).isEqualTo(RiskSeverity.MEDIUM);
            assertThat(service.atRisk(unlabelled(), justBelowHigh)).isTrue();
        }

        @Test
        @DisplayName("a MEDIUM sprint the model is unsure about is not flagged")
        void mediumWithoutAFlagIsTheHonestReading() {
            BigDecimal unsure = BigDecimal.valueOf(0.48);
            assertThat(service.severityOf(unsure)).isEqualTo(RiskSeverity.MEDIUM);
            assertThat(service.atRisk(unlabelled(), unsure)).isFalse();
        }
    }
}
