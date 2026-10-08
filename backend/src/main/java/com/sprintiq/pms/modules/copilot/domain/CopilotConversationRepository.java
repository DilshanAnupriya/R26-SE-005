package com.sprintiq.pms.modules.copilot.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CopilotConversationRepository extends JpaRepository<CopilotConversation, UUID> {

    List<CopilotConversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    /** Ownership is part of the lookup, so another user's id reads as not found. */
    Optional<CopilotConversation> findByIdAndUserId(UUID id, UUID userId);
}
