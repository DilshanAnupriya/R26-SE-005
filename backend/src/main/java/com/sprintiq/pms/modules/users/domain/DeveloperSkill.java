package com.sprintiq.pms.modules.users.domain;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

import java.util.UUID;

/**
 * One person's proficiency in one skill, 1 to 5.
 *
 * <p>Three is the threshold the allocation model treats as "can do this unsupervised".
 * Below it the skill still appears — a 2 is exactly what makes a task a good stretch
 * assignment — but it does not count towards the match ratio, because a ranking that
 * treated familiarity as competence would route production work to whoever had heard of
 * the technology.</p>
 */
@Entity
@Table(name = "developer_skills")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeveloperSkill extends TenantAwareEntity {

    public static final short WORKING_PROFICIENCY = 3;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Builder.Default
    @Column(name = "proficiency", nullable = false)
    private short proficiency = WORKING_PROFICIENCY;
}
