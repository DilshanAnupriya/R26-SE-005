package com.sprintiq.pms.integration;

import com.sprintiq.pms.common.error.TenantNotResolvedException;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.organizations.domain.Workspace;
import com.sprintiq.pms.modules.organizations.domain.WorkspaceRepository;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single most important test in the codebase: proves that one tenant's queries
 * cannot see another tenant's rows, and that a missing tenant context fails closed
 * rather than returning everything.
 */
@Transactional
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;

    /**
     * {@code organizations.owner_user_id} is a foreign key, so the owner has to be a real
     * row — a bare {@code UUID.randomUUID()} is rejected by the database.
     */
    private UUID newUser(String label) {
        String email = "%s+%s@example.test".formatted(label, UUID.randomUUID());
        User user = User.builder()
                .email(email)
                .emailNormalized(email.toLowerCase(java.util.Locale.ROOT))
                .fullName("Test %s".formatted(label))
                .status(UserStatus.ACTIVE)
                .build();
        return userRepository.saveAndFlush(user).getId();
    }

    @Test
    @DisplayName("a workspace query returns only the calling tenant's rows")
    void workspacesAreIsolatedByOrganization() {
        UUID userA = newUser("a");
        UUID userB = newUser("b");
        Organization orgA = provisioningService.provisionOrganization("Acme Robotics", userA);
        Organization orgB = provisioningService.provisionOrganization("Globex Media", userB);

        TenantContext.set(TenantContext.Context.builder()
                .organizationId(orgA.getId()).userId(userA).permissions(java.util.Set.of()).build());
        try {
            List<Workspace> visible = workspaceRepository.findAll();
            assertThat(visible)
                    .isNotEmpty()
                    .allMatch(workspace -> workspace.getOrganizationId().equals(orgA.getId()));
            assertThat(visible).noneMatch(workspace -> workspace.getOrganizationId().equals(orgB.getId()));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("tenant-scoped work without a context fails closed")
    void missingTenantContextThrows() {
        TenantContext.clear();
        assertThatThrownBy(TenantContext::requireOrganizationId)
                .isInstanceOf(TenantNotResolvedException.class);
    }
}
