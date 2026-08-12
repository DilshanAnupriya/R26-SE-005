package com.sprintiq.pms.modules.analytics;

import com.sprintiq.pms.modules.analytics.application.TimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timeline's date arithmetic.
 *
 * <p>The assembly itself is exercised by {@code TimelineIT} against real data; what is
 * worth isolating here is the day maths the client uses to place a bar, because an
 * off-by-one there draws every bar a day wide of where it belongs and nobody notices
 * until a deadline is read off the chart.</p>
 */
@DisplayName("Timeline arithmetic")
class TimelineWindowTest {

    @Test
    @DisplayName("counts whole days between two dates, endpoints included by the caller")
    void daysBetween() {
        assertThat(TimelineService.days(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8)))
                .isEqualTo(7);
        assertThat(TimelineService.days(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1)))
                .isZero();
    }

    @Test
    @DisplayName("handles a bar that ends before it starts without going negative in the UI")
    void reversedRange() {
        // Data can be wrong — an epic whose target was moved before its start. The
        // arithmetic reports it honestly and the client clamps, rather than the
        // backend quietly swapping the dates and hiding a data problem.
        assertThat(TimelineService.days(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 1)))
                .isEqualTo(-7);
    }

    @Test
    @DisplayName("spans a leap day like any other")
    void leapYear() {
        assertThat(TimelineService.days(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1)))
                .isEqualTo(2);
    }
}
