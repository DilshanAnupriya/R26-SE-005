package com.sprintiq.pms.modules.organizations.application;

import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.util.SlugGenerator;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.audit.domain.AuditAction;
import com.sprintiq.pms.modules.organizations.domain.*;
import com.sprintiq.pms.modules.organizations.web.dto.OrganizationDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository memberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public OrganizationResponse getCurrent() {
        UUID organizationId = TenantContext.requireOrganizationId();
        Organization organization = organizationRepository.findByIdAndDeletedAtIsNull(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", organizationId));
        return toResponse(organization);
    }

    /** Organizations the caller can switch into — the only cross-tenant read in the system. */
    @Transactional(readOnly = true)
    public List<OrganizationResponse> listMine(UUID userId) {
        return organizationRepository.findAllForUser(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public OrganizationResponse update(UpdateOrganizationRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();
        Organization organization = organizationRepository.findByIdAndDeletedAtIsNull(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", organizationId));

        Map<String, Object> before = snapshot(organization);

        if (request.name() != null && !request.name().equals(organization.getName())) {
            organization.setName(request.name().trim());
            // The slug is stable once issued: changing it would break existing links.
        }
        if (request.logoUrl() != null) organization.setLogoUrl(request.logoUrl());
        if (request.currency() != null) organization.setCurrency(request.currency());
        if (request.timezone() != null) organization.setTimezone(request.timezone());
        if (request.workingDays() != null) organization.setWorkingDays(request.workingDays());
        if (request.defaultHourlyRate() != null) organization.setDefaultHourlyRate(request.defaultHourlyRate());

        organization = organizationRepository.save(organization);
        auditService.record(AuditAction.ORGANIZATION_UPDATED, "Organization", organizationId,
                before, snapshot(organization));
        return toResponse(organization);
    }

    // ------------------------------------------------------------ workspaces

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> listWorkspaces() {
        return workspaceRepository
                .findByOrganizationIdAndDeletedAtIsNull(TenantContext.requireOrganizationId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse getWorkspace(UUID workspaceId) {
        return toResponse(requireWorkspace(workspaceId));
    }

    @Transactional
    public WorkspaceResponse createWorkspace(CreateWorkspaceRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();
        String key = (request.workspaceKey() == null || request.workspaceKey().isBlank())
                ? SlugGenerator.toKey(request.name(), 8)
                : request.workspaceKey();

        if (workspaceRepository.existsByOrganizationIdAndWorkspaceKey(organizationId, key)) {
            throw ConflictException.duplicate("Workspace", "key", key);
        }

        Workspace workspace = Workspace.builder()
                .name(request.name().trim())
                .workspaceKey(key)
                .description(request.description())
                .icon(request.icon())
                .defaultWorkspace(workspaceRepository
                        .findFirstByOrganizationIdAndDefaultWorkspaceTrue(organizationId).isEmpty())
                .build();
        workspace.setOrganizationId(organizationId);
        workspace = workspaceRepository.save(workspace);

        auditService.record(AuditAction.WORKSPACE_CREATED, "Workspace", workspace.getId(), null,
                Map.of("name", workspace.getName(), "key", key));
        return toResponse(workspace);
    }

    @Transactional
    public WorkspaceResponse updateWorkspace(UUID workspaceId, UpdateWorkspaceRequest request) {
        Workspace workspace = requireWorkspace(workspaceId);
        if (request.name() != null) workspace.setName(request.name().trim());
        if (request.description() != null) workspace.setDescription(request.description());
        if (request.icon() != null) workspace.setIcon(request.icon());
        return toResponse(workspaceRepository.save(workspace));
    }

    @Transactional
    public void deleteWorkspace(UUID workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.isDefaultWorkspace()) {
            throw new ConflictException("The default workspace cannot be deleted");
        }
        workspace.setDeletedAt(Instant.now());
        workspaceRepository.save(workspace);
    }

    // ------------------------------------------------------------ internals

    private Workspace requireWorkspace(UUID workspaceId) {
        return workspaceRepository.findByIdAndDeletedAtIsNull(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace", workspaceId));
    }

    private OrganizationResponse toResponse(Organization organization) {
        long members = memberRepository.countByOrganizationIdAndStatus(
                organization.getId(), MembershipStatus.ACTIVE);
        return new OrganizationResponse(organization.getId(), organization.getName(), organization.getSlug(),
                organization.getLogoUrl(), organization.getPlan().name(), organization.getStatus().name(),
                organization.getCurrency(), organization.getTimezone(), organization.getWorkingDays(),
                organization.getDefaultHourlyRate(), organization.getOwnerUserId(), members,
                organization.getCreatedAt());
    }

    private WorkspaceResponse toResponse(Workspace workspace) {
        return new WorkspaceResponse(workspace.getId(), workspace.getOrganizationId(), workspace.getName(),
                workspace.getWorkspaceKey(), workspace.getDescription(), workspace.getIcon(),
                workspace.isDefaultWorkspace(), workspace.getCreatedAt());
    }

    private Map<String, Object> snapshot(Organization organization) {
        Map<String, Object> state = new HashMap<>();
        state.put("name", organization.getName());
        state.put("currency", organization.getCurrency());
        state.put("timezone", organization.getTimezone());
        state.put("workingDays", organization.getWorkingDays());
        state.put("defaultHourlyRate", organization.getDefaultHourlyRate());
        return state;
    }
}
