package com.sprintiq.pms.modules.copilot.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A change the Copilot would like to make, waiting for a person.
 *
 * <p>The model can create these; only a confirm request from the owning user can carry
 * one out. That split is what makes the assistant safe to point at data the team
 * writes: an instruction hidden in a task title can at worst produce a card nobody
 * clicks.</p>
 */
@Entity
@Table(name = "copilot_proposals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CopilotProposal extends TenantAwareEntity {

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    /** Set once the assistant message that offered it has been saved. */
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24, updatable = false)
    private ProposalType type;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "summary", nullable = false, length = 500, updatable = false)
    private String summary;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProposalStatus status = ProposalStatus.PENDING;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "result_message", length = 500)
    private String resultMessage;

    public boolean isExpiredAt(Instant now) {
        return status == ProposalStatus.PENDING && expiresAt != null && now.isAfter(expiresAt);
    }

    /** The status a reader should see: a lapsed pending proposal reads as expired. */
    public ProposalStatus effectiveStatus(Instant now) {
        return isExpiredAt(now) ? ProposalStatus.EXPIRED : status;
    }

    public void resolve(ProposalStatus outcome, UUID by, String message, Instant at) {
        this.status = outcome;
        this.resolvedBy = by;
        this.resolvedAt = at;
        this.resultMessage = message == null ? null
                : message.length() > 500 ? message.substring(0, 500) : message;
    }
}
