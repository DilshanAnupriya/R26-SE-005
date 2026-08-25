package com.sprintiq.pms.modules.projects;

import com.sprintiq.pms.common.domain.Priority;
import com.sprintiq.pms.modules.projects.domain.ChangeImpactDirection;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeImpact;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeImpact.Snapshot;
import com.sprintiq.pms.modules.projects.domain.RequirementChangeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which way a requirement change pushed, read out of the edit.
 *
 * <p>The register used to ask. That made the risk score a function of what somebody
 * selected in a dropdown: two people making the same edit could charge the sprint
 * opposite signs, and nothing could tell afterwards which of them was right. These pin
 * the answers so the question stays settled.</p>
 *
 * <p>The measures are deliberately blunt — a priority's weight, a count of lines, a
 * length of text. What matters is not that they are subtle but that they are consistent
 * and explainable to the person who just made the edit.</p>
 */
@DisplayName("Requirement change direction")
class RequirementChangeImpactTest {

    private static final ChangeImpactDirection UP = ChangeImpactDirection.INCREASE;
    private static final ChangeImpactDirection DOWN = ChangeImpactDirection.DECREASE;

    private static Snapshot story(Priority priority, String description, String criteria,
                                  double points) {
        return new Snapshot(priority, description, criteria, BigDecimal.valueOf(points));
    }

    private static ChangeImpactDirection infer(RequirementChangeType type,
                                               Snapshot before, Snapshot after) {
        return RequirementChangeImpact.infer(type, before, after, null);
    }

    @Nested
    @DisplayName("Priority")
    class PriorityChanges {

        @Test
        @DisplayName("raised mid-sprint reads as harder")
        void raised() {
            assertThat(infer(RequirementChangeType.PRIORITY_CHANGED,
                    story(Priority.MEDIUM, null, null, 5),
                    story(Priority.CRITICAL, null, null, 5))).isEqualTo(UP);
        }

        @Test
        @DisplayName("dropped reads as easier")
        void dropped() {
            assertThat(infer(RequirementChangeType.PRIORITY_CHANGED,
                    story(Priority.HIGH, null, null, 5),
                    story(Priority.LOW, null, null, 5))).isEqualTo(DOWN);
        }

        @Test
        @DisplayName("unchanged is not a change at all")
        void unchanged() {
            assertThat(infer(RequirementChangeType.PRIORITY_CHANGED,
                    story(Priority.HIGH, null, null, 5),
                    story(Priority.HIGH, null, null, 5))).isNull();
        }
    }

    @Nested
    @DisplayName("Acceptance criteria")
    class Criteria {

        @Test
        @DisplayName("another criterion is another thing to satisfy")
        void added() {
            assertThat(infer(RequirementChangeType.ACCEPTANCE_CRITERIA_CHANGED,
                    story(Priority.MEDIUM, null, "Logs in\nSees the dashboard", 5),
                    story(Priority.MEDIUM, null, "Logs in\nSees the dashboard\nCan sign out", 5)))
                    .isEqualTo(UP);
        }

        @Test
        @DisplayName("dropping one reads as easier")
        void removed() {
            assertThat(infer(RequirementChangeType.ACCEPTANCE_CRITERIA_CHANGED,
                    story(Priority.MEDIUM, null, "Logs in\nSees the dashboard", 5),
                    story(Priority.MEDIUM, null, "Logs in", 5))).isEqualTo(DOWN);
        }

        @Test
        @DisplayName("blank lines are spacing, not criteria")
        void blankLinesDoNotCount() {
            assertThat(infer(RequirementChangeType.ACCEPTANCE_CRITERIA_CHANGED,
                    story(Priority.MEDIUM, null, "Logs in\nSees the dashboard", 5),
                    story(Priority.MEDIUM, null, "Logs in\n\n\nSees the dashboard", 5))).isNull();
        }

        @Test
        @DisplayName("a tightened rewrite at the same count still reads as harder")
        void tightenedAtTheSameCount() {
            // Two criteria before and after, but the second is far more specific. Counting
            // alone would call this no change and charge nothing for a real tightening.
            assertThat(infer(RequirementChangeType.ACCEPTANCE_CRITERIA_CHANGED,
                    story(Priority.MEDIUM, null, "Logs in\nWorks", 5),
                    story(Priority.MEDIUM, null,
                            "Logs in\nWorks on mobile, tablet and desktop, in both themes", 5)))
                    .isEqualTo(UP);
        }

        @Test
        @DisplayName("writing the first criteria onto a story with none reads as harder")
        void fromNothing() {
            assertThat(infer(RequirementChangeType.ACCEPTANCE_CRITERIA_CHANGED,
                    story(Priority.MEDIUM, null, null, 5),
                    story(Priority.MEDIUM, null, "Logs in", 5))).isEqualTo(UP);
        }
    }

    @Nested
    @DisplayName("Description")
    class Description {

        @Test
        @DisplayName("more detail reads as harder")
        void expanded() {
            assertThat(infer(RequirementChangeType.DESCRIPTION_UPDATED,
                    story(Priority.MEDIUM, "Export the report.", null, 5),
                    story(Priority.MEDIUM,
                            "Export the report, as CSV and PDF, including the archived rows.",
                            null, 5))).isEqualTo(UP);
        }

        @Test
        @DisplayName("cutting detail reads as easier")
        void trimmed() {
            assertThat(infer(RequirementChangeType.DESCRIPTION_UPDATED,
                    story(Priority.MEDIUM,
                            "Export the report, as CSV and PDF, including the archived rows.",
                            null, 5),
                    story(Priority.MEDIUM, "Export the report as CSV.", null, 5))).isEqualTo(DOWN);
        }

        @Test
        @DisplayName("reformatting is not a requirement change")
        void whitespaceOnly() {
            // Re-indenting a block or joining two lines changes the characters and not the
            // requirement. Charging a sprint for it would teach people not to tidy.
            assertThat(infer(RequirementChangeType.DESCRIPTION_UPDATED,
                    story(Priority.MEDIUM, "Export the report.", null, 5),
                    story(Priority.MEDIUM, "  Export   the\n  report.  ", null, 5))).isNull();
        }
    }

    @Nested
    @DisplayName("Scope")
    class Scope {

        @Test
        @DisplayName("carries its sign in its name, whatever the text says")
        void fixedDirections() {
            Snapshot same = story(Priority.MEDIUM, "unchanged", null, 5);

            // Nothing about the story moved, and scope added is still an increase — the
            // type is the statement. The service refuses the untouched-estimate case
            // separately; that is not this class's job.
            assertThat(infer(RequirementChangeType.SCOPE_ADDED, same, same)).isEqualTo(UP);
            assertThat(infer(RequirementChangeType.SCOPE_REMOVED, same, same)).isEqualTo(DOWN);
        }
    }

    @Nested
    @DisplayName("Other")
    class Other {

        @Test
        @DisplayName("stays the author's call, because nothing on the story moved")
        void fallsBackToTheRequest() {
            Snapshot same = story(Priority.MEDIUM, "unchanged", null, 5);

            assertThat(RequirementChangeImpact.infer(RequirementChangeType.OTHER, same, same, DOWN))
                    .isEqualTo(DOWN);
            assertThat(RequirementChangeImpact.infer(RequirementChangeType.OTHER, same, same, null))
                    .as("an unstated direction is charged as an increase rather than a credit")
                    .isEqualTo(UP);
        }
    }
}
