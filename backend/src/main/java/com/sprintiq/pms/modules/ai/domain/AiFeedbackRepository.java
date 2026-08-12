package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiFeedbackRepository extends JpaRepository<AiFeedback, UUID> {

    Optional<AiFeedback> findFirstByPredictionIdOrderByCreatedAtDesc(UUID predictionId);

    List<AiFeedback> findByPredictionIdIn(List<UUID> predictionIds);

    /**
     * Acceptance and mean absolute error per model, which is the whole of the
     * "is this model earning its place" question in one query.
     */
    @Query("""
            SELECT f.action AS action, COUNT(f) AS total, AVG(ABS(f.delta)) AS meanAbsoluteDelta
            FROM AiFeedback f
            JOIN AiPrediction p ON p.id = f.predictionId
            WHERE p.modelCode = :modelCode AND f.createdAt >= :since
            GROUP BY f.action
            """)
    List<FeedbackTally> tallyByAction(@Param("modelCode") AiModelCode modelCode,
                                      @Param("since") Instant since);

    /** Projection for {@link #tallyByAction}. */
    interface FeedbackTally {
        FeedbackAction getAction();

        long getTotal();

        Double getMeanAbsoluteDelta();
    }
}
