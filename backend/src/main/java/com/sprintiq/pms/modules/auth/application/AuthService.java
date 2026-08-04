package com.sprintiq.pms.modules.auth.application;

import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.common.util.SlugGenerator;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.audit.domain.AuditAction;
import com.sprintiq.pms.modules.audit.domain.AuditOutcome;
import com.sprintiq.pms.modules.auth.domain.*;
import com.sprintiq.pms.modules.auth.web.dto.AuthDtos.*;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.*;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.security.AppProperties;
import com.sprintiq.pms.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the credential lifecycle. Two rules shape most of the code here:
 * <ol>
 *   <li><b>No user enumeration.</b> Registration conflicts, unknown emails on password
 *       reset and wrong passwords all produce responses that cannot be used to probe
 *       which addresses exist.</li>
 *   <li><b>Refresh tokens are revocable.</b> They are opaque, hashed, rotated on every
 *       use, and grouped into families so replay of a spent token kills the session.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final OrganizationRepository organizationRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;
    private final OneTimeTokenRepository oneTimeTokenRepository;
    private final OrganizationProvisioningService provisioningService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties properties;
    private final AuditService auditService;
    private final AuthNotificationService notificationService;

    // ------------------------------------------------------------------ register

    @Transactional
    public AuthTokens register(RegisterRequest request, HttpServletRequest httpRequest) {
        String normalizedEmail = User.normalizeEmail(request.email());
        if (userRepository.existsByEmailNormalized(normalizedEmail)) {
            // Deliberately generic: confirming the address exists would leak account presence.
            throw new ApplicationException(ErrorCode.DUPLICATE_RESOURCE,
                    "That email cannot be used to register. Try signing in or resetting your password.");
        }

        User user = User.builder()
                .fullName(request.fullName().trim())
                .passwordHash(passwordEncoder.encode(request.password()))
                .status(UserStatus.ACTIVE)
                .timezone(Optional.ofNullable(request.timezone()).filter(s -> !s.isBlank()).orElse("UTC"))
                .build();
        user.setEmail(request.email());
        user = userRepository.save(user);

        Organization organization = provisioningService.provisionOrganization(
                request.organizationName(), user.getId());

        Role ownerRole = roleRepository.findByCodeAndSystemRoleTrue(Role.OWNER)
                .orElseThrow(() -> new IllegalStateException("System role OWNER is missing — check migration V5"));

        OrgMember membership = OrgMember.builder()
                .userId(user.getId())
                .roleId(ownerRole.getId())
                .status(MembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build();
        membership.setOrganizationId(organization.getId());
        orgMemberRepository.save(membership);

        notificationService.sendEmailVerification(user, issueOneTimeToken(user.getId(),
                TokenPurpose.EMAIL_VERIFICATION, properties.getSecurity().getEmailVerificationTtl()));

        auditService.recordAnonymous(AuditAction.USER_REGISTERED, user.getEmail(), organization.getId(),
                AuditOutcome.SUCCESS, Map.of("organizationName", organization.getName()));

        return issueTokens(user, organization, ownerRole, httpRequest);
    }

    // ------------------------------------------------------------------ login

    @Transactional
    public AuthTokens login(LoginRequest request, HttpServletRequest httpRequest) {
        String normalizedEmail = User.normalizeEmail(request.email());
        User user = userRepository.findByEmailNormalized(normalizedEmail).orElse(null);

        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            if (user != null) {
                user.recordFailedLogin(properties.getSecurity().getMaxFailedLogins(),
                        properties.getSecurity().getLockoutDuration());
                userRepository.save(user);
            }
            auditService.recordAnonymous(AuditAction.USER_LOGIN_FAILED, normalizedEmail, null,
                    AuditOutcome.FAILURE, Map.of("reason", "bad_credentials"));
            throw new ApplicationException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (user.isLocked()) {
            throw new ApplicationException(ErrorCode.ACCOUNT_LOCKED,
                    "Too many failed attempts. Try again later or reset your password.");
        }
        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DEACTIVATED) {
            throw new ApplicationException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                    "This account is not active. Contact your organization administrator.");
        }
        if (properties.getSecurity().isMfaEnabled() && user.isMfaEnabled()
                && (request.mfaCode() == null || request.mfaCode().isBlank())) {
            throw new ApplicationException(ErrorCode.MFA_REQUIRED);
        }

        OrgMember membership = resolveMembership(user.getId(), request.organizationId());
        Organization organization = organizationRepository.findByIdAndDeletedAtIsNull(membership.getOrganizationId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                        "Your organization is no longer available"));
        Role role = roleRepository.findById(membership.getRoleId())
                .orElseThrow(() -> new IllegalStateException("Membership references a missing role"));

        user.recordSuccessfulLogin();
        userRepository.save(user);

        auditService.recordAnonymous(AuditAction.USER_LOGIN, user.getEmail(), organization.getId(),
                AuditOutcome.SUCCESS, Map.of("role", role.getCode()));

        return issueTokens(user, organization, role, httpRequest);
    }

    // ------------------------------------------------------------------ refresh & logout

    @Transactional
    public AuthTokens refresh(String rawRefreshToken, HttpServletRequest httpRequest) {
        RefreshToken rotated = refreshTokenService.verifyAndRotate(rawRefreshToken, httpRequest);
        User user = userRepository.findById(rotated.getUserId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.TOKEN_INVALID));
        OrgMember membership = resolveMembership(user.getId(), rotated.getOrganizationId());
        Organization organization = organizationRepository.findByIdAndDeletedAtIsNull(membership.getOrganizationId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.TOKEN_INVALID));
        Role role = roleRepository.findById(membership.getRoleId())
                .orElseThrow(() -> new IllegalStateException("Membership references a missing role"));

        Set<String> permissions = roleRepository.findPermissionCodes(role.getId());
        String accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(), user.getFullName(),
                organization.getId(), role.getCode(), permissions);

        return AuthTokens.of(accessToken, refreshTokenService.rawValueOf(rotated),
                jwtService.accessTokenTtlSeconds(),
                buildAuthenticatedUser(user, organization, role, permissions));
    }

    /** Re-issues a token pair bound to a different organization the user already belongs to. */
    @Transactional
    public AuthTokens switchOrganization(UUID userId, UUID targetOrganizationId, HttpServletRequest httpRequest) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.UNAUTHENTICATED));
        OrgMember membership = orgMemberRepository
                .findByOrganizationIdAndUserId(targetOrganizationId, userId)
                .filter(OrgMember::isActive)
                .orElseThrow(() -> new ApplicationException(ErrorCode.ACCESS_DENIED,
                        "You are not a member of that organization"));
        Organization organization = organizationRepository.findByIdAndDeletedAtIsNull(targetOrganizationId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.NOT_FOUND, "Organization not found"));
        Role role = roleRepository.findById(membership.getRoleId()).orElseThrow();

        refreshTokenService.revokeAllForUser(userId);
        return issueTokens(user, organization, role, httpRequest);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeByRawToken(rawRefreshToken);
        auditService.record(AuditAction.USER_LOGOUT, "User", null, null, null);
    }

    @Transactional
    public void logoutAll(UUID userId) {
        refreshTokenService.revokeAllForUser(userId);
        auditService.record(AuditAction.USER_LOGOUT, "User", userId, null, Map.of("scope", "all-devices"));
    }

    // ------------------------------------------------------------------ password lifecycle

    /** Always succeeds from the caller's perspective — the response must not reveal account existence. */
    @Transactional
    public void requestPasswordReset(String email) {
        userRepository.findByEmailNormalized(User.normalizeEmail(email)).ifPresent(user -> {
            oneTimeTokenRepository.consumeOutstanding(user.getId(), TokenPurpose.PASSWORD_RESET, Instant.now());
            String rawToken = issueOneTimeToken(user.getId(), TokenPurpose.PASSWORD_RESET,
                    properties.getSecurity().getPasswordResetTtl());
            notificationService.sendPasswordReset(user, rawToken);
            auditService.recordAnonymous(AuditAction.PASSWORD_RESET_REQUESTED, user.getEmail(), null,
                    AuditOutcome.SUCCESS, Map.of());
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        OneTimeToken token = consumeOneTimeToken(request.token(), TokenPurpose.PASSWORD_RESET);
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.TOKEN_INVALID));

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFailedLoginCount((short) 0);
        user.setLockedUntil(null);
        if (user.getStatus() == UserStatus.PENDING) {
            user.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(user);

        // A password change invalidates every existing session.
        refreshTokenService.revokeAllForUser(user.getId());
        notificationService.sendPasswordChanged(user);
        auditService.recordAnonymous(AuditAction.PASSWORD_RESET_COMPLETED, user.getEmail(), null,
                AuditOutcome.SUCCESS, Map.of());
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.UNAUTHENTICATED));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApplicationException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(userId);
        notificationService.sendPasswordChanged(user);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        OneTimeToken token = consumeOneTimeToken(rawToken, TokenPurpose.EMAIL_VERIFICATION);
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.TOKEN_INVALID));
        user.setEmailVerifiedAt(Instant.now());
        if (user.getStatus() == UserStatus.PENDING) {
            user.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(user);
    }

    // ------------------------------------------------------------------ current user

    @Transactional(readOnly = true)
    public AuthenticatedUser currentUser(UUID userId, UUID organizationId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.UNAUTHENTICATED));
        OrgMember membership = resolveMembership(userId, organizationId);
        Organization organization = organizationRepository.findByIdAndDeletedAtIsNull(membership.getOrganizationId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.NOT_FOUND, "Organization not found"));
        Role role = roleRepository.findById(membership.getRoleId()).orElseThrow();
        return buildAuthenticatedUser(user, organization, role, roleRepository.findPermissionCodes(role.getId()));
    }

    // ------------------------------------------------------------------ internals

    private AuthTokens issueTokens(User user, Organization organization, Role role,
                                   HttpServletRequest httpRequest) {
        Set<String> permissions = roleRepository.findPermissionCodes(role.getId());
        String accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(), user.getFullName(),
                organization.getId(), role.getCode(), permissions);
        String refreshToken = refreshTokenService.issue(user.getId(), organization.getId(), httpRequest);
        return AuthTokens.of(accessToken, refreshToken, jwtService.accessTokenTtlSeconds(),
                buildAuthenticatedUser(user, organization, role, permissions));
    }

    private AuthenticatedUser buildAuthenticatedUser(User user, Organization organization, Role role,
                                                     Set<String> permissions) {
        List<OrganizationSummary> organizations = organizationRepository.findAllForUser(user.getId()).stream()
                .map(org -> new OrganizationSummary(org.getId(), org.getName(), org.getSlug(), org.getLogoUrl(),
                        orgMemberRepository.findByOrganizationIdAndUserId(org.getId(), user.getId())
                                .flatMap(m -> roleRepository.findById(m.getRoleId()))
                                .map(Role::getCode).orElse(null)))
                .toList();

        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(),
                user.getTimezone(), user.getLocale(), user.isEmailVerified(), user.isMfaEnabled(),
                user.getLastLoginAt(), organization.getId(), organization.getName(), role.getCode(),
                permissions, organizations);
    }

    private OrgMember resolveMembership(UUID userId, UUID preferredOrganizationId) {
        if (preferredOrganizationId != null) {
            return orgMemberRepository.findByOrganizationIdAndUserId(preferredOrganizationId, userId)
                    .filter(OrgMember::isActive)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.ACCESS_DENIED,
                            "You are not an active member of that organization"));
        }
        return orgMemberRepository.findByUserIdAndStatus(userId, MembershipStatus.ACTIVE).stream()
                .findFirst()
                .orElseThrow(() -> new ApplicationException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                        "This account does not belong to any organization yet"));
    }

    private String issueOneTimeToken(UUID userId, TokenPurpose purpose, java.time.Duration ttl) {
        String raw = com.sprintiq.pms.common.util.TokenHasher.generateToken();
        oneTimeTokenRepository.save(OneTimeToken.builder()
                .userId(userId)
                .purpose(purpose)
                .tokenHash(com.sprintiq.pms.common.util.TokenHasher.hash(raw))
                .expiresAt(Instant.now().plus(ttl))
                .build());
        return raw;
    }

    private OneTimeToken consumeOneTimeToken(String rawToken, TokenPurpose purpose) {
        OneTimeToken token = oneTimeTokenRepository
                .findByTokenHashAndPurpose(com.sprintiq.pms.common.util.TokenHasher.hash(rawToken), purpose)
                .orElseThrow(() -> new ApplicationException(ErrorCode.TOKEN_INVALID,
                        "This link is invalid or has already been used"));
        if (!token.isRedeemable()) {
            throw new ApplicationException(ErrorCode.TOKEN_EXPIRED, "This link has expired. Request a new one.");
        }
        token.setConsumedAt(Instant.now());
        oneTimeTokenRepository.save(token);
        return token;
    }
}
