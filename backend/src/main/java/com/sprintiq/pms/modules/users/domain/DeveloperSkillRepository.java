package com.sprintiq.pms.modules.users.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeveloperSkillRepository extends JpaRepository<DeveloperSkill, UUID> {

    List<DeveloperSkill> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);

    /**
     * Skill names and proficiencies for a group of people, in one query.
     *
     * <p>The allocation panel ranks a whole team at once. Loading each person's skills
     * separately would put a query per candidate behind a single button press.</p>
     */
    @Query("""
            SELECT ds.userId AS userId, s.name AS skillName, ds.proficiency AS proficiency
            FROM DeveloperSkill ds
            JOIN Skill s ON s.id = ds.skillId
            WHERE ds.userId IN :userIds
            """)
    List<UserSkillRow> findSkillsFor(@Param("userIds") Collection<UUID> userIds);

    /** Projection for {@link #findSkillsFor}. */
    interface UserSkillRow {
        UUID getUserId();

        String getSkillName();

        short getProficiency();
    }
}
