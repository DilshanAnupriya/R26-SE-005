package com.sprintiq.pms.modules.organizations.application;

import com.sprintiq.pms.common.error.*;
import com.sprintiq.pms.common.util.TokenHasher;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.audit.domain.AuditAction;
import com.sprintiq.pms.modules.auth.application.AuthNotificationService;
import com.sprintiq.pms.modules.organizations.domain.*;
import com.sprintiq.pms.modules.organizations.web.dto.OrganizationDtos.*;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.security.AppProperties;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Members, invitations and role assignment inside one organization.
 *
 * <p>The invariant worth calling out: an organization must always retain at least one
 * active OWNER. Every path that could remove the last owner — role change, member
 * removal, self-removal — is guarded, otherwise a tenant can be permanently orphaned.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MembershipService {

    private static final Set<String> MEMBER_SORT_FIELDS = Set.of("createdAt", "joinedAt", "status");

    private final OrgMemberRepository memberRepository;
    private final InvitationRepository invitationRepository;
    private final OrganizationRepository organizationRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final AuditService auditService;
    private final AuthNotificationService notificationService;

    // ------------------------------------------------------------ listing

    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> listMembers(PageQuery query) {
        UUID organizationId = TenantContext.requireOrganizationId();
        Pageable pageable = query.toPageable(MEMBER_SORT_FIELDS, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<OrgMember> page = memberRepository.findByOrganizationId(organizationId, pageable);

        List<UUID> userIds = page.getContent().stream().map(OrgMember::getUserId).toList();
        Map<UUID, User> users = userRepository.findByIdIn(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        Map<UUID, Role> roles = roleRepository.findAvailableRoles(organizationId).stream()
                .collect(java.util.stream.Collectors.toMap(Role::getId, r -> r));

        return PageResponse.from(page, member -> toMemberResponse(member,
                users.get(member.getUserId()), roles.get(member.getRoleId())));
    }

    // ------------------------------------------------------------ invitations

    @Transactional
    public InvitationResponse invite(CreateInvitationRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();
        String email = User.normalizeEmail(request.email());

        Role role = roleRepository.findAccessibleRole(request.roleId(), organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", request.roleId()));

        userRepository.findByEmailNormalized(email).ifPresent(existing -> {
            if (memberRepository.existsByOrganizationIdAndUserIdAndStatus(
                    organizationId, existing.getId(), MembershipStatus.ACTIVE)) {
                throw new ConflictException("That person is already a member of this organization");
            }
        });

        // Re-inviting supersedes any outstanding invitation rather than stacking duplicates.
        invitationRepository.findFirstByOrganizationIdAndEmailAndStatus(
                organizationId, email, InvitationStatus.PENDING)
                .ifPresent(pending -> {
                    pending.setStatus(InvitationStatus.REVOKED);
                    invitationRepository.save(pending);
                });

        String rawToken = TokenHasher.generateToken();
        Invitation invitation = Invitation.builder()
                .email(email)
                .roleId(role.getId())
                .workspaceId(request.workspaceId())
                .tokenHash(TokenHasher.hash(rawToken))
                .status(InvitationStatus.PENDING)
                .expiresAt(Instant.now().plus(properties.getSecurity().getInvitationTtl()))
                .invitedBy(TenantContext.requireUserId())
                .build();
        invitation.setOrganizationId(organizationId);
        invitation = invitationRepository.save(invitation);

        Organization organization = organizationRepository.getReferenceById(organizationId);
        String inviterName = TenantContext.get().map(TenantContext.Context::userEmail).orElse("A teammate");
        notificationService.sendInvitation(email, organization.getName(), inviterName, rawToken);

        auditService.record(AuditAction.MEMBER_INVITED, "Invitation", invitation.getId(), null,
                Map.of("email", email, "role", role.getCode()));

        return toInvitationResponse(invitation, role);
    }

    @Transactional(readOnly = true)
    public PageResponse<InvitationResponse> listInvitations(InvitationStatus status, PageQuery query) {
        UUID organizationId = TenantContext.requireOrganizationId();
        Pageable pageable = query.toPageable(Set.of("createdAt", "expiresAt", "email"),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Invitation> page = invitationRepository.findByOrganizationIdAndStatus(
                organizationId, status == null ? InvitationStatus.PENDING : status, pageable);
        Map<UUID, Role> roles = roleRepository.findAvailableRoles(organizationId).stream()
                .collect(java.util.stream.Collectors.toMap(Role::getId, r -> r));
        return PageResponse.from(page, invitation -> toInvitationResponse(invitation,
                roles.get(invitation.getRoleId())));
    }

    @Transactional
    public void revokeInvitation(UUID invitationId) {
        Invitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", invitationId));
        invitation.setStatus(InvitationStatus.REVOKED);
        invitationRepository.save(invitation);
    }

    /**
     * Redeemed by an anonymous caller: the token itself is the credential, so this runs
     * outside the tenant filter and resolves the organization from the invitation row.
     */
    @Transactional
    public UUID acceptInvitation(AcceptInvitationRequest request) {
        Invitation invitation = invitationRepository.findByTokenHash(TokenHasher.hash(request.token()))
                .orElseThrow(() -> new ApplicationException(ErrorCode.TOKEN_INVALID,
                        "This invitation link is not valid"));
        if (!invitation.isRedeemable()) {
            throw new ApplicationException(ErrorCode.TOKEN_EXPIRED,
                    "This invitation has expired or was revoked. Ask for a new one.");
        }

        User user = userRepository.findByEmailNormalized(invitation.getEmail())
                .orElseGet(() -> createInvitedUser(invitation, request));

        UUID organizationId = invitation.getOrganizationId();
        OrgMember membership = memberRepository.findByOrganizationIdAndUserId(organizationId, user.getId())
                .orElseGet(() -> {
                    OrgMember created = OrgMember.builder()
                            .userId(user.getId())
                            .roleId(invitation.getRoleId())
                            .invitedBy(invitation.getInvitedBy())
                            .build();
                    created.setOrganizationId(organizationId);
                    return created;
                });
        membership.setStatus(MembershipStatus.ACTIVE);
        membership.setJoinedAt(Instant.now());
        memberRepository.save(membership);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(Instant.now());
        invitationRepository.save(invitation);

        auditService.recordAnonymous(AuditAction.MEMBER_JOINED, user.getEmail(), organizationId,
                com.sprintiq.pms.modules.audit.domain.AuditOutcome.SUCCESS, Map.of());
        return organizationId;
    }

    // ------------------------------------------------------------ role changes

    @Transactional
    public MemberResponse changeRole(UUID targetUserId, UpdateMemberRoleRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();
        OrgMember membership = memberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", targetUserId));
        Role newRole = roleRepository.findAccessibleRole(request.roleId(), organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", request.roleId()));
        Role currentRole = roleRepository.findById(membership.getRoleId()).orElseThrow();

        if (Role.OWNER.equals(currentRole.getCode()) && !Role.OWNER.equals(newRole.getCode())) {
            assertNotLastOwner(organizationId, currentRole.getId());
        }

        membership.setRoleId(newRole.getId());
        memberRepository.save(membership);

        auditService.record(AuditAction.MEMBER_ROLE_CHANGED, "OrgMember", membership.getId(),
                Map.of("role", currentRole.getCode()), Map.of("role", newRole.getCode()));

        return toMemberResponse(membership, userRepository.findById(targetUserId).orElseThrow(), newRole);
    }

    @Transactional
    public void removeMember(UUID targetUserId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        OrgMember membership = memberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", targetUserId));
        Role role = roleRepository.findById(membership.getRoleId()).orElseThrow();

        if (Role.OWNER.equals(role.getCode())) {
            assertNotLastOwner(organizationId, role.getId());
        }

        membership.setStatus(MembershipStatus.REMOVED);
        memberRepository.save(membership);
        auditService.record(AuditAction.MEMBER_REMOVED, "OrgMember", membership.getId(), null,
                Map.of("userId", targetUserId.toString()));
    }

    // ------------------------------------------------------------ internals

    private void assertNotLastOwner(UUID organizationId, UUID ownerRoleId) {
        long owners = memberRepository.countByOrganizationIdAndRoleIdAndStatus(
                organizationId, ownerRoleId, MembershipStatus.ACTIVE);
        if (owners <= 1) {
            throw new BusinessRuleException(
                    "An organization must keep at least one owner. Promote another member first.");
        }
    }

    private User createInvitedUser(Invitation invitation, AcceptInvitationRequest request) {
        if (request.password() == null || request.fullName() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_ERROR,
                    "New members must supply a name and password to accept an invitation");
        }
        User user = User.builder()
                .fullName(request.fullName().trim())
                .passwordHash(passwordEncoder.encode(request.password()))
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())   // the invitation email itself proves control of the address
                .build();
        user.setEmail(invitation.getEmail());
        return userRepository.save(user);
    }

    private MemberResponse toMemberResponse(OrgMember member, User user, Role role) {
        return new MemberResponse(
                member.getUserId(),
                user != null ? user.getEmail() : null,
                user != null ? user.getFullName() : null,
                user != null ? user.getAvatarUrl() : null,
                user != null ? user.getJobTitle() : null,
                member.getRoleId(),
                role != null ? role.getCode() : null,
                role != null ? role.getName() : null,
                member.getStatus().name(),
                member.getJoinedAt(),
                user != null ? user.getLastLoginAt() : null);
    }

    private InvitationResponse toInvitationResponse(Invitation invitation, Role role) {
        return new InvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRoleId(),
                role != null ? role.getCode() : null, invitation.getStatus().name(),
                invitation.getExpiresAt(), invitation.getCreatedAt(), invitation.getInvitedBy());
    }
}
