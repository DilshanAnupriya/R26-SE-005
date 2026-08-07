package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.EntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiPredictionRepository extends JpaRepository<AiPrediction, UUID> {

    /**
     * Cache lookup. Ordered newest-first and limited by the caller's {@code Pageable}
     * because the same feature vector can legitimately appear many times — every cache
     * hit writes its own row so that usage is countable.
     */
    @Query("""
            SELECT p FROM AiPrediction p
            WHERE p.modelCode = :modelCode
              AND p.featureHash = :featureHash
              AND p.status = com.sprintiq.pms.modules.ai.domain.PredictionStatus.SUCCESS
              AND p.cacheHit = false
              AND p.createdAt >= :notBefore
            ORDER BY p.createdAt DESC
            """)
    List<AiPrediction> findCacheable(@Param("modelCode") AiModelCode modelCode,
                                     @Param("featureHash") String featureHash,
                                     @Param("notBefore") Instant notBefore,
                                     Pageable pageable);

    Optional<AiPrediction> findFirstByModelCodeAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
            AiModelCode modelCode, EntityType entityType, UUID entityId);

    List<AiPrediction> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(EntityType entityType, UUID entityId);

    @Query("""
            SELECT p FROM AiPrediction p
            WHERE (:modelCode IS NULL OR p.modelCode = :modelCode)
              AND (:entityType IS NULL OR p.entityType = :entityType)
              AND (:entityId IS NULL OR p.entityId = :entityId)
              AND (:projectId IS NULL OR p.projectId = :projectId)
              AND (CAST(:from AS timestamp) IS NULL OR p.createdAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR p.createdAt <= :to)
            """)
    Page<AiPrediction> search(@Param("modelCode") AiModelCode modelCode,
                              @Param("entityType") EntityType entityType,
                              @Param("entityId") UUID entityId,
                              @Param("projectId") UUID projectId,
                              @Param("from") Instant from,
                              @Param("to") Instant to,
                              Pageable pageable);

    long countByModelCodeAndCreatedAtAfter(AiModelCode modelCode, Instant since);

    long countByModelCodeAndStatusAndCreatedAtAfter(AiModelCode modelCode, PredictionStatus status, Instant since);
}
