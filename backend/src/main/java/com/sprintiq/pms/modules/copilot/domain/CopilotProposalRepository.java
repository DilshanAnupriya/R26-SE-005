package com.sprintiq.pms.modules.copilot.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CopilotProposalRepository extends JpaRepository<CopilotProposal, UUID> {

    List<CopilotProposal> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /**
     * Locked for the duration of a confirm, so a double click cannot run the action twice:
     * the second request waits, then sees the proposal is no longer pending.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM CopilotProposal p WHERE p.id = :id AND p.userId = :userId")
    Optional<CopilotProposal> findForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);
}
