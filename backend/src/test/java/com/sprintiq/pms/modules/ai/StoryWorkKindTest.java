package com.sprintiq.pms.modules.ai;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.modules.ai.application.port.StoryFeatureSource.StoryFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three work-kind columns SprintIQ used to leave blank.
 *
 * <p>Before this, every story reached the model with these unset and the service
 * keyword-matched the text — which meant a story labelled "security" whose description
 * happened to avoid the word scored as ordinary work. These tests pin the order of
 * preference: a label or a dependency is evidence, the wording is a fallback.</p>
 */
@DisplayName("Story work kind")
class StoryWorkKindTest {

    private StoryFeatures story(String title, String description, int dependencies,
                                List<String> labels) {
        return new StoryFeatures(UUID.randomUUID(), UUID.randomUUID(), "PAY-1",
                title, description, null, 0, "STORY", Priority.MEDIUM,
                labels.size(), dependencies, 0, false, null,
                BigDecimal.valueOf(5), BigDecimal.valueOf(5), 3, Instant.now(), labels);
    }

    @Nested
    @DisplayName("integration")
    class Integration {

        @Test
        @DisplayName("a dependency edge is integration work whatever the wording says")
        void dependencyAlone() {
            // The strongest case for deriving this in the backend: the board knows this
            // story waits on other work, and no amount of reading the sentence would
            // have found that out.
            assertThat(story("Tidy the footer", "Move the links", 1, List.of())
                    .hasIntegrationWork()).isTrue();
        }

        @Test
        @DisplayName("a label counts even when the text never says so")
        void labelAlone() {
            assertThat(story("Nightly job", "Run at 2am", 0, List.of("integration"))
                    .hasIntegrationWork()).isTrue();
        }

        @Test
        @DisplayName("falls back to the wording, so an unlabelled story is no worse off")
        void keywordFallback() {
            assertThat(story("Add webhook for orders", null, 0, List.of())
                    .hasIntegrationWork()).isTrue();
        }

        @Test
        @DisplayName("plain work is not integration work")
        void plainWork() {
            assertThat(story("Rename the column header", "Cosmetic", 0, List.of())
                    .hasIntegrationWork()).isFalse();
        }

        @Test
        @DisplayName("an Export button is the system's own feature, not an integration")
        void exportInTheWordingIsNotIntegration() {
            assertThat(story("Add a tooltip to the Export button",
                    "Explain what the export contains when a user hovers over the button.",
                    0, List.of("ui")).hasIntegrationWork()).isFalse();
        }

        @Test
        @DisplayName("a person's export label still counts")
        void exportLabelStillCounts() {
            assertThat(story("Monthly report file", "Send it to finance", 0, List.of("export"))
                    .hasIntegrationWork()).isTrue();
        }
    }

    @Nested
    @DisplayName("security")
    class Security {

        @Test
        @DisplayName("a label wins where the wording is silent")
        void labelAlone() {
            assertThat(story("Session timeout", "Log people out", 0, List.of("security"))
                    .hasSecurityWork()).isTrue();
        }

        @Test
        @DisplayName("matches a label that only contains the word")
        void partialLabel() {
            assertThat(story("Roles", "Tidy up", 0, List.of("needs-security-review"))
                    .hasSecurityWork()).isTrue();
        }

        @Test
        @DisplayName("a dependency does not make a story security work")
        void dependencyIsNotSecurity() {
            // Separate signals for separate columns. Conflating them would set two
            // columns off one fact and double-count it.
            assertThat(story("Export CSV", "Nightly", 2, List.of()).hasSecurityWork())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("UI complexity")
    class Ui {

        @Test
        @DisplayName("reads the label")
        void labelAlone() {
            assertThat(story("Rework the flow", "Fewer steps", 0, List.of("ux"))
                    .hasUiComplexity()).isTrue();
        }

        @Test
        @DisplayName("reads the wording when there is no label")
        void keywordFallback() {
            assertThat(story("New dashboard layout", null, 0, List.of())
                    .hasUiComplexity()).isTrue();
        }

        @Test
        @DisplayName("backend work is not UI work")
        void backendWork() {
            assertThat(story("Reindex the audit table", "Nightly batch", 0, List.of())
                    .hasUiComplexity()).isFalse();
        }
    }

    @Test
    @DisplayName("a story with nothing at all answers false rather than throwing")
    void emptyStory() {
        StoryFeatures bare = story(null, null, 0, List.of());
        assertThat(bare.hasIntegrationWork()).isFalse();
        assertThat(bare.hasSecurityWork()).isFalse();
        assertThat(bare.hasUiComplexity()).isFalse();
    }
}
