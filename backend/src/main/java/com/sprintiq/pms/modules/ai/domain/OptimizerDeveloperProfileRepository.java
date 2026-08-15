package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface OptimizerDeveloperProfileRepository extends JpaRepository<DeveloperProfile, Long> {
    Optional<DeveloperProfile> findByDeveloperMetricsId(Long developerId);
}
