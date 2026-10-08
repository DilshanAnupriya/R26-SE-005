package com.sprintiq.pms.integration;

import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.copilot.application.ConversationService;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ConversationDetail;
import com.sprintiq.pms.modules.copilot.web.dto.CopilotDtos.ConversationSummary;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Copilot conversations against real Postgres: the migration applies, and a thread is
 * private to the person who started it — not merely to their organisation.
 */
@DisplayName("Copilot conversations")
class CopilotIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired UserRepository userRepository;
    @Autowired ConversationService conversations;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newUser(String label) {
        String email = "%s+%s@example.test".formatted(label, UUID.randomUUID());
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .emailNormalized(email.toLowerCase(Locale.ROOT))
                .fullName("Test %s".formatted(label))
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())
                .build()).getId();
    }

    private static void actAs(UUID organizationId, UUID userId) {
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organizationId).userId(userId)
                .permissions(Set.of("ai:predict")).systemContext(false).build());
    }

    @Test
    @DisplayName("a conversation is visible to its owner only")
    void privateToOwner() {
        UUID owner = newUser("owner");
        Organization org = provisioningService.provisionOrganization("Acme Robotics", owner);
        UUID colleague = newUser("colleague");

        UUID outsider = newUser("outsider");
        Organization otherOrg = provisioningService.provisionOrganization("Other Co", outsider);

        actAs(org.getId(), owner);
        ConversationSummary mine = conversations.create();
        conversations.saveTurn(conversations.requireOwned(mine.id()),
                "Which sprint is most at risk this week?", "Sprint 7, at 70%.", "test-model", java.util.List.of());

        ConversationDetail detail = conversations.get(mine.id());
        assertThat(detail.title()).isEqualTo("Which sprint is most at risk this week?");
        assertThat(detail.messages()).hasSize(2);
        assertThat(conversations.list()).extracting(ConversationSummary::id).contains(mine.id());

        actAs(org.getId(), colleague);
        assertThat(conversations.list()).isEmpty();
        assertThatThrownBy(() -> conversations.get(mine.id())).isInstanceOf(ResourceNotFoundException.class);

        actAs(otherOrg.getId(), outsider);
        assertThat(conversations.list()).isEmpty();
        assertThatThrownBy(() -> conversations.get(mine.id())).isInstanceOf(ResourceNotFoundException.class);

        actAs(org.getId(), owner);
        conversations.delete(mine.id());
        assertThat(conversations.list()).extracting(ConversationSummary::id).doesNotContain(mine.id());
    }
}
