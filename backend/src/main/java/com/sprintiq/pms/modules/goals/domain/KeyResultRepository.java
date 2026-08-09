package com.sprintiq.pms.modules.goals.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KeyResultRepository extends JpaRepository<KeyResult, UUID> {

    Optional<KeyResult> findByIdAndDeletedAtIsNull(UUID id);

    List<KeyResult> findByGoalIdAndDeletedAtIsNullOrderByPositionAsc(UUID goalId);

    /** Batch form, so a page of goals resolves its results in one query rather than N. */
    List<KeyResult> findByGoalIdInAndDeletedAtIsNull(Collection<UUID> goalIds);

    long countByGoalIdAndDeletedAtIsNull(UUID goalId);
}
