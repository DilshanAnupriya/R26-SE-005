package com.sprintiq.pms.modules.sprints;

import com.sprintiq.pms.modules.projects.application.ProjectDeliveryHistorySource.DeliveryHistory;
import com.sprintiq.pms.modules.sprints.application.SprintDeliveryHistoryProvider;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Velocity and completion rate, measured rather than typed.
 *
 * <p>Both were project columns a human filled in once at creation and nothing ever
 * updated. The model read them as descriptions of the team delivering today, which they
 * stopped being after the first sprint. These pin the measurement and, more importantly,
 * what happens when there is nothing to measure.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint delivery history")
class SprintDeliveryHistoryProviderTest {

    private static final UUID PROJECT = UUID.randomUUID();

    @Mock private SprintRepository sprintRepository;

    private Sprint completed(double committed, double done) {
        return Sprint.builder()
                .projectId(PROJECT)
                .committedPoints(BigDecimal.valueOf(committed))
                .completedPoints(BigDecimal.valueOf(done))
                .build();
    }

    private DeliveryHistory historyOf(List<Sprint> sprints) {
        when(sprintRepository.findCompletedHistory(eq(PROJECT), any())).thenReturn(sprints);
        return new SprintDeliveryHistoryProvider(sprintRepository).historyFor(PROJECT);
    }

    @Test
    @DisplayName("averages completed points across the window")
    void averagesVelocity() {
        DeliveryHistory history = historyOf(List.of(
                completed(30, 24), completed(30, 18), completed(30, 21)));

        assertThat(history.velocityAvg()).isEqualByComparingTo("21.00");
        assertThat(history.sprintsObserved()).isEqualTo(3);
    }

    @Test
    @DisplayName("pools the completion rate rather than averaging each sprint's own")
    void poolsCompletionRate() {
        // A two-point sprint that finished one would drag a mean of ratios exactly as
        // hard as a forty-point sprint that finished twenty. Pooling weights each
        // sprint by the work it actually carried.
        DeliveryHistory history = historyOf(List.of(completed(2, 1), completed(40, 36)));

        // 37 of 42, not the 0.95 a mean of ratios would give.
        assertThat(history.completionRate()).isEqualByComparingTo("0.881");
    }

    @Test
    @DisplayName("a team that overdelivers is reported at 100%, not more")
    void capsAtOne() {
        // The model's column is a rate in 0..1. A sprint that pulled extra work in and
        // finished it is genuinely good news, but 1.4 is outside anything the model was
        // trained on and would be read as an outlier rather than as excellence.
        DeliveryHistory history = historyOf(List.of(completed(10, 14)));

        assertThat(history.completionRate()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("no completed sprint reports no history — not a zero velocity")
    void noHistory() {
        DeliveryHistory history = historyOf(List.of());

        assertThat(history.hasHistory()).isFalse();
        assertThat(history.velocityAvg()).isNull();
        assertThat(history.completionRate()).isNull();
    }

    @Test
    @DisplayName("a sprint that committed nothing leaves the rate absent rather than dividing by zero")
    void zeroCommitment() {
        DeliveryHistory history = historyOf(List.of(completed(0, 0)));

        assertThat(history.velocityAvg()).isEqualByComparingTo("0.00");
        assertThat(history.completionRate()).isNull();
    }

    @Test
    @DisplayName("reads at most five sprints, so old form counts less than recent form")
    void windowIsFive() {
        historyOf(List.of(completed(10, 10)));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(sprintRepository).findCompletedHistory(eq(PROJECT), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(5);
    }
}
