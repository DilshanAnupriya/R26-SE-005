package com.sprintiq.pms.modules.roadmap;

import com.sprintiq.pms.modules.roadmap.application.port.LinkedWork;
import com.sprintiq.pms.modules.roadmap.domain.Initiative;
import com.sprintiq.pms.modules.roadmap.domain.InitiativeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How far through an initiative is, and whether that is good enough for the date.
 *
 * <p>Plain unit tests — no Spring, no database. The interesting behaviour is
 * arithmetic and a calendar, and a container would add thirty seconds to prove
 * nothing extra.</p>
 */
@DisplayName("Initiative progress and pacing")
class InitiativeProgressTest {

    /** Every case here has epics attached; the empty case is its own test. */
    private static final boolean HAS_WORK = true;

    private static Initiative dated(LocalDate start, LocalDate target) {
        return Initiative.builder()
                .title("Ship SSO")
                .startDate(start)
                .targetDate(target)
                .build();
    }

    @Nested
    @DisplayName("linked work")
    class Work {

        @Test
        @DisplayName("prefers points over item counts when the team estimated")
        void pointsWin() {
            // Five one-point chores done and one thirteen-point story outstanding is not
            // 83% of the way there, which is what counting items would report.
            LinkedWork work = new LinkedWork("PAY-1", "SSO", true, 6, 5,
                    BigDecimal.valueOf(18), BigDecimal.valueOf(5));
            assertThat(work.progressPct()).isEqualByComparingTo("27.78");
        }

        @Test
        @DisplayName("falls back to item counts when nothing is estimated")
        void countsWhenUnestimated() {
            LinkedWork work = new LinkedWork("PAY-1", "SSO", true, 4, 1,
                    BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(work.progressPct()).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("an epic with no stories is zero, not a division by zero")
        void emptyEpic() {
            LinkedWork work = new LinkedWork("PAY-1", "SSO", true, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(work.progressPct()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a link whose target was deleted reports nothing and says so")
        void missingTarget() {
            assertThat(LinkedWork.missing().exists()).isFalse();
            assertThat(LinkedWork.missing().progressPct()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("status derivation")
    class Status {

        @Test
        @DisplayName("is planned on day one rather than at risk")
        void dayOneIsPlanned() {
            // Every initiative is at 0% the day it is created. Calling that AT_RISK
            // paints the whole roadmap red in week one and teaches everyone to ignore
            // the colour.
            Initiative initiative = dated(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
            initiative.recalculate(BigDecimal.ZERO, LocalDate.of(2026, 1, 1), HAS_WORK);

            assertThat(initiative.getStatus()).isEqualTo(InitiativeStatus.PLANNED);
        }

        @Test
        @DisplayName("is at risk when it falls far enough behind the calendar")
        void behindPace() {
            Initiative initiative = dated(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

            // Halfway through the quarter at 40% is 10 behind — fine.
            initiative.recalculate(BigDecimal.valueOf(40), LocalDate.of(2026, 2, 14), HAS_WORK);
            assertThat(initiative.getStatus()).isEqualTo(InitiativeStatus.IN_PROGRESS);

            // Halfway through at 5% is 45 behind.
            initiative.recalculate(BigDecimal.valueOf(5), LocalDate.of(2026, 2, 14), HAS_WORK);
            assertThat(initiative.getStatus()).isEqualTo(InitiativeStatus.AT_RISK);
        }

        @Test
        @DisplayName("all the work being done does not make it shipped")
        void completeIsNotShipped() {
            // "Every ticket is closed" and "it is in customers' hands" are different
            // claims, and only a person can make the second one.
            Initiative initiative = dated(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
            initiative.recalculate(BigDecimal.valueOf(100), LocalDate.of(2026, 2, 14), HAS_WORK);

            assertThat(initiative.getProgressPct()).isEqualByComparingTo("100.00");
            assertThat(initiative.getStatus()).isEqualTo(InitiativeStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("a shipped initiative keeps its verdict when late work lands")
        void shippedSticks() {
            Initiative initiative = Initiative.builder()
                    .title("Last quarter")
                    .status(InitiativeStatus.SHIPPED)
                    .startDate(LocalDate.of(2026, 1, 1))
                    .targetDate(LocalDate.of(2026, 3, 31))
                    .build();

            initiative.recalculate(BigDecimal.valueOf(40), LocalDate.of(2026, 4, 30), HAS_WORK);

            // Progress still updates — the number is the number — but the verdict stands.
            assertThat(initiative.getProgressPct()).isEqualByComparingTo("40.00");
            assertThat(initiative.getStatus()).isEqualTo(InitiativeStatus.SHIPPED);
        }

        @Test
        @DisplayName("an undated initiative is judged only on movement")
        void undated() {
            Initiative initiative = Initiative.builder().title("Someday").build();

            initiative.recalculate(BigDecimal.ZERO, LocalDate.of(2026, 2, 14), HAS_WORK);
            assertThat(initiative.getStatus()).isEqualTo(InitiativeStatus.PLANNED);

            initiative.recalculate(BigDecimal.valueOf(20), LocalDate.of(2026, 2, 14), HAS_WORK);
            assertThat(initiative.getStatus()).isEqualTo(InitiativeStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("statusOn answers without changing anything")
        void statusOnIsPure() {
            Initiative initiative = dated(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
            initiative.recalculate(BigDecimal.valueOf(40), LocalDate.of(2026, 2, 14), HAS_WORK);

            assertThat(initiative.statusOn(LocalDate.of(2026, 3, 28), HAS_WORK))
                    .isEqualTo(InitiativeStatus.AT_RISK);
            assertThat(initiative.getStatus()).isEqualTo(InitiativeStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("stays planned when nothing is linked, however late it is")
        void nothingLinkedIsNeverAtRisk() {
            // An initiative with no epics attached is a card somebody drew, not work a
            // team is behind on. Marking it AT_RISK blames them for a planning gap and
            // fills the roadmap with red that no amount of delivery can clear.
            Initiative initiative = dated(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

            initiative.recalculate(BigDecimal.ZERO, LocalDate.of(2026, 3, 28), false);

            assertThat(initiative.getStatus()).isEqualTo(InitiativeStatus.PLANNED);
        }

        @Test
        @DisplayName("progress is clamped, so one runaway link cannot exceed the whole")
        void clamped() {
            Initiative initiative = dated(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

            initiative.recalculate(BigDecimal.valueOf(140), LocalDate.of(2026, 2, 14), HAS_WORK);
            assertThat(initiative.getProgressPct()).isEqualByComparingTo("100.00");

            initiative.recalculate(BigDecimal.valueOf(-20), LocalDate.of(2026, 2, 14), HAS_WORK);
            assertThat(initiative.getProgressPct()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("the roadmap column")
    class Quarter {

        @Test
        @DisplayName("is derived from the target date, so a slip moves the card by itself")
        void derivedFromTarget() {
            assertThat(dated(null, LocalDate.of(2026, 8, 10)).quarter()).isEqualTo("2026-Q3");
            assertThat(dated(null, LocalDate.of(2026, 1, 1)).quarter()).isEqualTo("2026-Q1");
            assertThat(dated(null, LocalDate.of(2026, 12, 31)).quarter()).isEqualTo("2026-Q4");
        }

        @Test
        @DisplayName("sorts as text, which is why the year comes first")
        void sortsAsText() {
            // "2026-Q4" < "2027-Q1" as strings. Naming them "Q4 2026" would have sorted
            // the roadmap columns alphabetically into nonsense.
            assertThat(Initiative.quarterOf(LocalDate.of(2026, 11, 1)))
                    .isLessThan(Initiative.quarterOf(LocalDate.of(2027, 2, 1)));
        }

        @Test
        @DisplayName("is absent for an initiative with no target date")
        void undatedHasNoColumn() {
            assertThat(dated(null, null).quarter()).isNull();
        }
    }
}
