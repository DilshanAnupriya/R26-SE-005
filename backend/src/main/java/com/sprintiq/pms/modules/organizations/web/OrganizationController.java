package com.sprintiq.pms.modules.organizations.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.organizations.application.MembershipService;
import com.sprintiq.pms.modules.organizations.application.OrganizationService;
import com.sprintiq.pms.modules.organizations.application.RoleService;
import com.sprintiq.pms.modules.organizations.domain.InvitationStatus;
import com.sprintiq.pms.modules.organizations.web.dto.OrganizationDtos.*;
import com.sprintiq.pms.security.AppPrincipal;
import com.sprintiq.pms.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Organizations", description = "Tenant settings, members, invitations and roles")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;
    private final MembershipService membershipService;
    private final RoleService roleService;

    // ---------------- organization ----------------

    @Operation(summary = "Organizations the current user belongs to (powers the switcher)")
    @GetMapping
    public List<OrganizationResponse> listMine(@CurrentUser AppPrincipal principal) {
        return organizationService.listMine(principal.userId());
    }

    @Operation(summary = "Active organization")
    @PreAuthorize("@perm.has('org:read')")
    @GetMapping("/current")
    public OrganizationResponse current() {
        return organizationService.getCurrent();
    }

    @Operation(summary = "Update organization settings")
    @PreAuthorize("@perm.has('org:update')")
    @PatchMapping("/current")
    public OrganizationResponse update(@Valid @RequestBody UpdateOrganizationRequest request) {
        return organizationService.update(request);
    }

    // ---------------- members ----------------

    @Operation(summary = "List members")
    @PreAuthorize("@perm.has('member:read')")
    @GetMapping("/current/members")
    public PageResponse<MemberResponse> listMembers(@Valid PageQuery query) {
        return membershipService.listMembers(query);
    }

    @Operation(summary = "Change a member's role")
    @PreAuthorize("@perm.has('member:update')")
    @PatchMapping("/current/members/{userId}/role")
    public MemberResponse changeRole(@PathVariable UUID userId,
                                     @Valid @RequestBody UpdateMemberRoleRequest request) {
        return membershipService.changeRole(userId, request);
    }

    @Operation(summary = "Remove a member")
    @PreAuthorize("@perm.has('member:remove')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/current/members/{userId}")
    public void removeMember(@PathVariable UUID userId) {
        membershipService.removeMember(userId);
    }

    // ---------------- invitations ----------------

    @Operation(summary = "Invite someone to the organization")
    @PreAuthorize("@perm.has('member:invite')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/current/invitations")
    public InvitationResponse invite(@Valid @RequestBody CreateInvitationRequest request) {
        return membershipService.invite(request);
    }

    @Operation(summary = "List invitations")
    @PreAuthorize("@perm.has('member:invite')")
    @GetMapping("/current/invitations")
    public PageResponse<InvitationResponse> listInvitations(
            @RequestParam(required = false) InvitationStatus status, @Valid PageQuery query) {
        return membershipService.listInvitations(status, query);
    }

    @Operation(summary = "Revoke an invitation")
    @PreAuthorize("@perm.has('member:invite')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/current/invitations/{invitationId}")
    public void revokeInvitation(@PathVariable UUID invitationId) {
        membershipService.revokeInvitation(invitationId);
    }

    @Operation(summary = "Accept an invitation",
            description = "Anonymous: the token in the emailed link is the credential.")
    @SecurityRequirements
    @PostMapping("/invitations/accept")
    public Map<String, UUID> acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        return Map.of("organizationId", membershipService.acceptInvitation(request));
    }

    // ---------------- roles ----------------

    @Operation(summary = "Roles available to this organization")
    @PreAuthorize("@perm.has('role:read')")
    @GetMapping("/current/roles")
    public List<RoleResponse> listRoles() {
        return roleService.listRoles();
    }

    @Operation(summary = "Permission catalogue grouped by resource")
    @PreAuthorize("@perm.has('role:read')")
    @GetMapping("/permissions")
    public List<PermissionCatalogResponse> permissions() {
        return roleService.permissionCatalog();
    }

    @Operation(summary = "Create a custom role")
    @PreAuthorize("@perm.has('role:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/current/roles")
    public RoleResponse createRole(@Valid @RequestBody CreateRoleRequest request) {
        return roleService.createRole(request);
    }

    @Operation(summary = "Update a custom role")
    @PreAuthorize("@perm.has('role:manage')")
    @PatchMapping("/current/roles/{roleId}")
    public RoleResponse updateRole(@PathVariable UUID roleId, @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.updateRole(roleId, request);
    }

    @Operation(summary = "Delete a custom role")
    @PreAuthorize("@perm.has('role:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/current/roles/{roleId}")
    public void deleteRole(@PathVariable UUID roleId) {
        roleService.deleteRole(roleId);
    }
}
