package com.sprintiq.pms.modules.organizations.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class OrganizationDtos {

    private OrganizationDtos() {}

    // ---------------- organization ----------------

    @Schema(name = "UpdateOrganizationRequest")
    public record UpdateOrganizationRequest(
            @Size(min = 2, max = 150) String name,
            @Size(max = 512) String logoUrl,
            @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code") String currency,
            @Size(max = 64) String timezone,
            @Min(1) @Max(7) Short workingDays,
            @DecimalMin("0.0") BigDecimal defaultHourlyRate) {}

    @Schema(name = "OrganizationResponse")
    public record OrganizationResponse(
            UUID id, String name, String slug, String logoUrl, String plan, String status,
            String currency, String timezone, short workingDays, BigDecimal defaultHourlyRate,
            UUID ownerUserId, long memberCount, Instant createdAt) {}

    // ---------------- workspace ----------------

    @Schema(name = "CreateWorkspaceRequest")
    public record CreateWorkspaceRequest(
            @NotBlank @Size(min = 2, max = 150) String name,
            @Size(max = 16) @Pattern(regexp = "^[A-Z0-9]*$", message = "Key must be upper-case letters and digits")
            String workspaceKey,
            @Size(max = 2000) String description,
            @Size(max = 64) String icon) {}

    @Schema(name = "UpdateWorkspaceRequest")
    public record UpdateWorkspaceRequest(
            @Size(min = 2, max = 150) String name,
            @Size(max = 2000) String description,
            @Size(max = 64) String icon) {}

    @Schema(name = "WorkspaceResponse")
    public record WorkspaceResponse(
            UUID id, UUID organizationId, String name, String workspaceKey, String description,
            String icon, boolean defaultWorkspace, Instant createdAt) {}

    // ---------------- membership ----------------

    @Schema(name = "MemberResponse")
    public record MemberResponse(
            UUID userId, String email, String fullName, String avatarUrl, String jobTitle,
            UUID roleId, String roleCode, String roleName, String status,
            Instant joinedAt, Instant lastLoginAt) {}

    @Schema(name = "UpdateMemberRoleRequest")
    public record UpdateMemberRoleRequest(@NotNull UUID roleId) {}

    // ---------------- invitations ----------------

    @Schema(name = "CreateInvitationRequest")
    public record CreateInvitationRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotNull UUID roleId,
            UUID workspaceId) {}

    @Schema(name = "InvitationResponse")
    public record InvitationResponse(
            UUID id, String email, UUID roleId, String roleCode, String status,
            Instant expiresAt, Instant createdAt, UUID invitedBy, String inviteToken) {}

    @Schema(name = "InvitationDetailsResponse")
    public record InvitationDetailsResponse(
            String email, String organizationName, String roleCode, String roleName,
            String inviterName, Instant expiresAt) {}

    @Schema(name = "AcceptInvitationRequest",
            description = "Existing users omit the password fields; new users supply them to create an account")
    public record AcceptInvitationRequest(
            @NotBlank String token,
            @Size(min = 2, max = 150) String fullName,
            @Size(min = 8, max = 128) String password) {}

    // ---------------- roles ----------------

    @Schema(name = "CreateRoleRequest")
    public record CreateRoleRequest(
            @NotBlank @Size(min = 2, max = 60) String name,
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String code,
            @Size(max = 255) String description,
            @NotEmpty Set<String> permissionCodes) {}

    @Schema(name = "UpdateRoleRequest")
    public record UpdateRoleRequest(
            @Size(min = 2, max = 60) String name,
            @Size(max = 255) String description,
            Set<String> permissionCodes) {}

    @Schema(name = "RoleResponse")
    public record RoleResponse(
            UUID id, String name, String code, String description, boolean systemRole,
            short rank, long memberCount, Set<String> permissionCodes) {}

    @Schema(name = "PermissionResponse")
    public record PermissionResponse(String code, String resource, String action, String description) {}

    @Schema(name = "PermissionCatalogResponse", description = "Permissions grouped by resource for the matrix editor")
    public record PermissionCatalogResponse(String resource, List<PermissionResponse> permissions) {}
}
