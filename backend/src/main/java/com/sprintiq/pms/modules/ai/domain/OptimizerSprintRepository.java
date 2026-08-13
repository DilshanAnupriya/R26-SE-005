package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface OptimizerSprintRepository extends JpaRepository<Sprint, Long> {
    Optional<Sprint> findBySprintId(String sprintId);
    List<Sprint> findByStatus(String status);
}
