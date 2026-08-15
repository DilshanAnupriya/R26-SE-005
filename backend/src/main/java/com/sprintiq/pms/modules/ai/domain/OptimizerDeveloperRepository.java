package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface OptimizerDeveloperRepository extends JpaRepository<Developer, Long> {
    @Query("SELECT d FROM OptimizerDeveloper d WHERE d.dev_id = :dev_id")
    Optional<Developer> findByDev_id(@Param("dev_id") String dev_id);
}
