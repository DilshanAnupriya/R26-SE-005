package com.sprintiq.pms.integration;

import com.sprintiq.pms.modules.goals.application.GoalService;
import com.sprintiq.pms.modules.goals.domain.GoalRepository;
import com.sprintiq.pms.modules.goals.domain.GoalStatus;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.goals.web.dto.GoalDtos.*;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nightly sweep that re-paces open goals against the calendar.
 *
 * <p>The interesting part is not the arithmetic — that is unit-tested — but the read:
 * the sweep runs on a scheduler thread with no tenant context, so the Hibernate filter
 * is off and it must see goals belonging to organizations nobody has logged into. That
 * only shows up against a real database with more than one tenant in it.</p>
 */
@Transactional
class GoalPacingIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired UserRepository userRepository;
    @Autowired GoalService goalService;
    @Autowired GoalRepository goalRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** A goal that opened months ago and has not moved since — behind pace by now. */
    private UUID staleGoalIn(String orgName) {
        UUID userId = newUser();
        Organization organization = provisioningService.provisionOrganization(orgName, userId);
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organization.getId())
                .userId(userId)
                .permissions(Set.of())
                .systemContext(false)
                .build());

        LocalDate today = LocalDate.now();
        GoalResponse goal = goalService.create(new CreateGoalRequest(
                "Ship onboarding", null, "Legacy cycle",
                // Opened 100 days ago, due in 5. Straight-line pacing puts the
                // expectation at ~95%, and nothing has been recorded against it.
                today.minusDays(100), today.plusDays(5),
                null, GoalStatus.ON_TRACK, null, null, null, null,
                List.of(new CreateKeyResultRequest("Activation rate", null, "%",
                        BigDecimal.ZERO, BigDecimal.valueOf(60), null, null))));

        // Force the stored verdict back to the value it would have held on day one,
        // which is exactly the state the defect left goals in: a green status frozen
        // at the moment of the last write.
        goalRepository.findById(goal.id()).orElseThrow().setStatus(GoalStatus.ON_TRACK);
        return goal.id();
    }

    private UUID newUser() {
        String email = "owner+%s@example.test".formatted(UUID.randomUUID());
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .emailNormalized(email.toLowerCase(Locale.ROOT))
                .fullName("Test owner")
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(java.time.Instant.now())
                .build()).getId();
    }

    @Test
    @DisplayName("re-paces goals in every organization, including ones nobody is signed into")
    void repacesAcrossTenants() {
        UUID first = staleGoalIn("Acme Robotics");
        UUID second = staleGoalIn("Globex Industrial");

        // The scheduler has no tenant bound, which is what lets it cross organizations.
        TenantContext.clear();
        goalService.repaceOpenGoals();

        assertThat(goalRepository.findById(first).orElseThrow().getStatus())
                .isEqualTo(GoalStatus.OFF_TRACK);
        assertThat(goalRepository.findById(second).orElseThrow().getStatus())
                .isEqualTo(GoalStatus.OFF_TRACK);
    }

    @Test
    @DisplayName("leaves a goal that is keeping pace alone")
    void leavesHealthyGoalsAlone() {
        UUID userId = newUser();
        Organization organization = provisioningService.provisionOrganization("Initech", userId);
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organization.getId())
                .userId(userId)
                .permissions(Set.of())
                .systemContext(false)
                .build());

        LocalDate today = LocalDate.now();
        GoalResponse goal = goalService.create(new CreateGoalRequest(
                "Just opened", null, "Current cycle",
                today, today.plusDays(90), null, null, null, null, null, null,
                List.of(new CreateKeyResultRequest("Activation rate", null, "%",
                        BigDecimal.ZERO, BigDecimal.valueOf(60), null, null))));
        assertThat(goal.status()).isEqualTo(GoalStatus.ON_TRACK);

        TenantContext.clear();
        goalService.repaceOpenGoals();

        assertThat(goalRepository.findById(goal.id()).orElseThrow().getStatus())
                .isEqualTo(GoalStatus.ON_TRACK);
    }
}
