package com.sprintiq.pms.modules.organizations.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /** Redemption happens before authentication, so this query is intentionally tenant-agnostic. */
    Optional<Invitation> findByTokenHash(String tokenHash);

    Page<Invitation> findByOrganizationIdAndStatus(UUID organizationId, InvitationStatus status, Pageable pageable);

    Optional<Invitation> findFirstByOrganizationIdAndEmailAndStatus(UUID organizationId, String email,
                                                                   InvitationStatus status);

    @Modifying
    @Query("UPDATE Invitation i SET i.status = 'EXPIRED' WHERE i.status = 'PENDING' AND i.expiresAt < :now")
    int expireStaleInvitations(@Param("now") Instant now);
}
