package com.sprintiq.pms.modules.organizations.application;

import com.sprintiq.pms.common.util.SlugGenerator;
import com.sprintiq.pms.modules.organizations.domain.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Creates a brand-new tenant and everything it needs to be immediately usable:
 * the organization row, a default workspace and the default AI feature flags.
 *
 * <p>Runs inside {@code TenantContext.runAs} because these writes happen before the
 * caller has any organization to be scoped to — the tenant literally does not exist yet.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationProvisioningService {

    private static final String[] DEFAULT_AI_FLAGS = {
            "ai.story_points", "ai.velocity", "ai.risk", "ai.allocation", "ai.cost"
    };

    private final OrganizationRepository organizationRepository;
    private final WorkspaceRepository workspaceRepository;

    @Transactional
    public Organization provisionOrganization(String name, UUID ownerUserId) {
        String slug = SlugGenerator.uniqueSlug(name, organizationRepository::existsBySlug);

        Organization organization = organizationRepository.save(Organization.builder()
                .name(name.trim())
                .slug(slug)
                .ownerUserId(ownerUserId)
                .plan(OrganizationPlan.FREE)
                .status(OrganizationStatus.ACTIVE)
                .build());

        TenantContext.runAs(organization.getId(), ownerUserId, () -> {
            Workspace workspace = Workspace.builder()
                    .name("General")
                    .workspaceKey(SlugGenerator.toKey(name, 8))
                    .description("Default workspace for " + organization.getName())
                    .defaultWorkspace(true)
                    .build();
            workspace.setOrganizationId(organization.getId());
            workspaceRepository.save(workspace);
        });

        log.info("Provisioned organization {} ({})", organization.getName(), organization.getId());
        return organization;
    }

    public String[] defaultAiFlags() {
        return DEFAULT_AI_FLAGS.clone();
    }
}
