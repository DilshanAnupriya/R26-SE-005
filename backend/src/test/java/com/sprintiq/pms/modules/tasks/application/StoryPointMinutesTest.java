package com.sprintiq.pms.modules.tasks.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A story card's estimate on the board, across the whole Planning Poker deck. The case
 * worth pinning is the half point: truncating before multiplying made it zero minutes.
 */
@DisplayName("Story points as board minutes")
class StoryPointMinutesTest {

    @ParameterizedTest(name = "{0} points -> {1} minutes")
    @CsvSource({"0, 0", "0.5, 30", "1, 60", "13, 780", "20, 1200", "40, 2400", "100, 6000"})
    void everyCardOnTheDeckBecomesItsMinutes(String points, int minutes) {
        assertThat(TaskService.storyPointMinutes(new BigDecimal(points))).isEqualTo(minutes);
    }
}
