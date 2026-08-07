package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AllocationSuggestionRepository extends JpaRepository<AllocationSuggestion, UUID> {

    List<AllocationSuggestion> findByPredictionIdOrderByRankPositionAsc(UUID predictionId);

    List<AllocationSuggestion> findByTaskIdOrderByCreatedAtDescRankPositionAsc(UUID taskId);

    List<AllocationSuggestion> findByStoryIdOrderByCreatedAtDescRankPositionAsc(UUID storyId);
}
