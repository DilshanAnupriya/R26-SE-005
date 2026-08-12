package com.sprintiq.pms.modules.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID> {

    Optional<OneTimeToken> findByTokenHashAndPurpose(String tokenHash, TokenPurpose purpose);

    @Modifying
    @Query("UPDATE OneTimeToken t SET t.consumedAt = :now WHERE t.userId = :userId AND t.purpose = :purpose AND t.consumedAt IS NULL")
    int consumeOutstanding(@Param("userId") UUID userId, @Param("purpose") TokenPurpose purpose,
                           @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM OneTimeToken t WHERE t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
