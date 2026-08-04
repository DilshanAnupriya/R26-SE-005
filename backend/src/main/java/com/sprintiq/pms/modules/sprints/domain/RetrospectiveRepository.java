package com.sprintiq.pms.modules.sprints.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RetrospectiveRepository extends JpaRepository<Retrospective, UUID> {

    Optional<Retrospective> findBySprintId(UUID sprintId);
}
