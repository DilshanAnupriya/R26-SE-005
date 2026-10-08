package com.sprintiq.pms.modules.copilot.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CopilotMessageRepository extends JpaRepository<CopilotMessage, UUID> {

    List<CopilotMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** Newest first; the caller reverses. Bounded so a long thread costs the same per turn. */
    List<CopilotMessage> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);
}
