package com.sprintiq.pms.modules.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request and response payloads for the authentication endpoints. */
public final class AuthDtos {

    private AuthDtos() {}

    private static final String PASSWORD_RULE =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,128}$";
    private static final String PASSWORD_MESSAGE =
            "Password must be at least 12 characters and include lower case, upper case, a digit and a symbol";

    @Schema(name = "RegisterRequest", description = "Creates the first user and their organization")
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String password,
            @NotBlank @Size(min = 2, max = 150) String fullName,
            @NotBlank @Size(min = 2, max = 150) String organizationName,
            @Size(max = 64) String timezone) {}

    @Schema(name = "LoginRequest")
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(max = 128) String password,
            @Schema(description = "Optional: log straight into a specific organization")
            UUID organizationId,
            @Size(max = 10) String mfaCode) {}

    @Schema(name = "RefreshRequest")
    public record RefreshRequest(@NotBlank String refreshToken) {}

    @Schema(name = "ForgotPasswordRequest")
    public record ForgotPasswordRequest(@NotBlank @Email String email) {}

    @Schema(name = "ResetPasswordRequest")
    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String newPassword) {}

    @Schema(name = "ChangePasswordRequest")
    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String newPassword) {}

    @Schema(name = "VerifyEmailRequest")
    public record VerifyEmailRequest(@NotBlank String token) {}

    @Schema(name = "SwitchOrganizationRequest")
    public record SwitchOrganizationRequest(@NotBlank String refreshToken, UUID organizationId) {}

    @Schema(name = "AuthTokens", description = "Access token plus rotating refresh token")
    public record AuthTokens(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            AuthenticatedUser user) {

        public static AuthTokens of(String access, String refresh, long expiresIn, AuthenticatedUser user) {
            return new AuthTokens(access, refresh, "Bearer", expiresIn, user);
        }
    }

    @Schema(name = "AuthenticatedUser", description = "Principal plus the memberships needed by the org switcher")
    public record AuthenticatedUser(
            UUID id,
            String email,
            String fullName,
            String avatarUrl,
            String timezone,
            String locale,
            boolean emailVerified,
            boolean mfaEnabled,
            Instant lastLoginAt,
            UUID activeOrganizationId,
            String activeOrganizationName,
            String activeRole,
            Set<String> permissions,
            List<OrganizationSummary> organizations) {}

    @Schema(name = "OrganizationSummary")
    public record OrganizationSummary(UUID id, String name, String slug, String logoUrl, String roleCode) {}
}
