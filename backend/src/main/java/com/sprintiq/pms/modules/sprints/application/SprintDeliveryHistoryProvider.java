package com.sprintiq.pms.modules.sprints.application;

import com.sprintiq.pms.modules.projects.application.ProjectDeliveryHistorySource;
import com.sprintiq.pms.modules.sprints.domain.Sprint;
import com.sprintiq.pms.modules.sprints.domain.SprintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Measures velocity and completion rate off the sprints that actually finished.
 *
 * <p>Only {@code COMPLETED} sprints count. An active sprint's completed points are a
 * partial total that climbs all week, so folding it in would make a team's measured
 * velocity depend on which day somebody opened the estimation panel.</p>
 */
@Component
@RequiredArgsConstructor
public class SprintDeliveryHistoryProvider implements ProjectDeliveryHistorySource {

    /**
     * Sprints in the window.
     *
     * <p>Five, matching what the velocity model was trained to read and what a team
     * recognises as "recently". A lifetime average would keep a team anchored to how it
     * performed before it learned the codebase, which is the opposite of what a planning
     * input is for.</p>
     */
    private static final int WINDOW = 5;

    private final SprintRepository sprintRepository;

    @Override
    @Transactional(readOnly = true)
    public DeliveryHistory historyFor(UUID projectId) {
        List<Sprint> completed =
                sprintRepository.findCompletedHistory(projectId, PageRequest.of(0, WINDOW));
        if (completed.isEmpty()) {
            return DeliveryHistory.none();
        }

        BigDecimal completedPoints = BigDecimal.ZERO;
        BigDecimal committedPoints = BigDecimal.ZERO;
        for (Sprint sprint : completed) {
            completedPoints = completedPoints.add(orZero(sprint.getCompletedPoints()));
            committedPoints = committedPoints.add(orZero(sprint.getCommittedPoints()));
        }

        BigDecimal velocity = completedPoints
                .divide(BigDecimal.valueOf(completed.size()), 2, RoundingMode.HALF_UP);

        // Pooled rather than the mean of each sprint's own rate: a sprint that committed
        // two points and finished one would otherwise drag the average as hard as one
        // that committed forty and finished twenty.
        BigDecimal completionRate = committedPoints.signum() <= 0
                ? null
                : completedPoints.divide(committedPoints, 3, RoundingMode.HALF_UP)
                        .min(BigDecimal.ONE);

        return new DeliveryHistory(velocity, completionRate, completed.size());
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
