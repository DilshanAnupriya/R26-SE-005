package com.sprintiq.pms.modules.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiModelRepository extends JpaRepository<AiModel, UUID> {

    Optional<AiModel> findByCode(AiModelCode code);

    List<AiModel> findAllByOrderByCodeAsc();
}
