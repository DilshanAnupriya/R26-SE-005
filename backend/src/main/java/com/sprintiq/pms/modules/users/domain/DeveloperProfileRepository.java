package com.sprintiq.pms.modules.users.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeveloperProfileRepository extends JpaRepository<DeveloperProfile, UUID> {

    Optional<DeveloperProfile> findByUserId(UUID userId);

    List<DeveloperProfile> findByUserIdIn(Collection<UUID> userIds);

    /**
     * Blended hourly rate across a set of people, ignoring those without one.
     *
     * <p>Ignoring rather than defaulting: a team where two of five have rates recorded
     * should be priced from those two, not from three imaginary zeroes that would halve
     * the forecast and make it look reassuringly affordable.</p>
     */
    @Query("""
            SELECT AVG(p.hourlyRate) FROM DeveloperProfile p
            WHERE p.userId IN :userIds AND p.hourlyRate IS NOT NULL
            """)
    BigDecimal averageHourlyRate(@Param("userIds") Collection<UUID> userIds);
}
