package com.sprintiq.pms.modules.organizations.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Only the SHA-256 hash of the invitation token is stored; the raw value lives in the email link. */
@Entity
@Table(name = "invitations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invitation extends TenantAwareEntity {

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isRedeemable() {
        return status == InvitationStatus.PENDING && !isExpired();
    }
}
