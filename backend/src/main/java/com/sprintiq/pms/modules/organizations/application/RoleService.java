package com.sprintiq.pms.modules.organizations.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ConflictException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.audit.domain.AuditAction;
import com.sprintiq.pms.modules.organizations.domain.*;
import com.sprintiq.pms.modules.organizations.web.dto.OrganizationDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Custom role management. System roles are read-only for every tenant — they are the
 * safety net that guarantees a sane permission set exists even if an admin misconfigures
 * their custom roles.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final OrgMemberRepository memberRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        UUID organizationId = TenantContext.requireOrganizationId();
        return roleRepository.findAvailableRoles(organizationId).stream()
                .map(role -> toResponse(role, organizationId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionCatalogResponse> permissionCatalog() {
        Map<String, List<PermissionResponse>> grouped = permissionRepository
                .findAllByOrderByResourceAscActionAsc().stream()
                .map(p -> new PermissionResponse(p.getCode(), p.getResource(), p.getAction(), p.getDescription()))
                .collect(Collectors.groupingBy(PermissionResponse::resource, LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> new PermissionCatalogResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Transactional
    @CacheEvict(value = "rolePermissions", allEntries = true)
    public RoleResponse createRole(CreateRoleRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();

        boolean codeTaken = roleRepository.findAvailableRoles(organizationId).stream()
                .anyMatch(existing -> existing.getCode().equalsIgnoreCase(request.code()));
        if (codeTaken) {
            throw ConflictException.duplicate("Role", "code", request.code());
        }

        Role role = Role.builder()
                .organizationId(organizationId)
                .name(request.name().trim())
                .code(request.code().toUpperCase(Locale.ROOT))
                .description(request.description())
                .systemRole(false)
                .rank((short) 60)
                .permissions(resolvePermissions(request.permissionCodes()))
                .build();
        role = roleRepository.save(role);

        auditService.record(AuditAction.ROLE_CREATED, "Role", role.getId(), null,
                Map.of("code", role.getCode(), "permissions", request.permissionCodes()));
        return toResponse(role, organizationId);
    }

    @Transactional
    @CacheEvict(value = "rolePermissions", allEntries = true)
    public RoleResponse updateRole(UUID roleId, UpdateRoleRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();
        Role role = roleRepository.findAccessibleRole(roleId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));

        if (role.isSystemRole()) {
            throw new BusinessRuleException("Built-in roles cannot be modified. Duplicate it as a custom role instead.");
        }

        Set<String> before = role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet());
        if (request.name() != null) role.setName(request.name().trim());
        if (request.description() != null) role.setDescription(request.description());
        if (request.permissionCodes() != null) role.setPermissions(resolvePermissions(request.permissionCodes()));
        role = roleRepository.save(role);

        auditService.record(AuditAction.ROLE_UPDATED, "Role", roleId,
                Map.of("permissions", before), Map.of("permissions", request.permissionCodes()));
        return toResponse(role, organizationId);
    }

    @Transactional
    @CacheEvict(value = "rolePermissions", allEntries = true)
    public void deleteRole(UUID roleId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        Role role = roleRepository.findAccessibleRole(roleId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));
        if (role.isSystemRole()) {
            throw new BusinessRuleException("Built-in roles cannot be deleted");
        }
        long assigned = memberRepository.countByOrganizationIdAndRoleIdAndStatus(
                organizationId, roleId, MembershipStatus.ACTIVE);
        if (assigned > 0) {
            throw new ConflictException(
                    "%d member(s) still hold this role. Reassign them before deleting it.".formatted(assigned));
        }
        roleRepository.delete(role);
    }

    private Set<Permission> resolvePermissions(Set<String> codes) {
        if (codes == null || codes.isEmpty()) return new HashSet<>();
        List<Permission> found = permissionRepository.findByCodeIn(List.copyOf(codes));
        if (found.size() != codes.size()) {
            Set<String> resolved = found.stream().map(Permission::getCode).collect(Collectors.toSet());
            Set<String> unknown = new HashSet<>(codes);
            unknown.removeAll(resolved);
            throw new BusinessRuleException("Unknown permission code(s): " + String.join(", ", unknown));
        }
        return new HashSet<>(found);
    }

    private RoleResponse toResponse(Role role, UUID organizationId) {
        long memberCount = memberRepository.countByOrganizationIdAndRoleIdAndStatus(
                organizationId, role.getId(), MembershipStatus.ACTIVE);
        Set<String> permissions = roleRepository.findPermissionCodes(role.getId());
        return new RoleResponse(role.getId(), role.getName(), role.getCode(), role.getDescription(),
                role.isSystemRole(), role.getRank(), memberCount, permissions);
    }
}
