package com.sprintiq.pms.modules.users.application;

import com.sprintiq.pms.common.error.AccessDeniedAppException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.modules.users.web.dto.UserDtos.*;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Directory and profile operations. Because {@code users} is a global table, every read
 * here is joined through {@code org_members} so one tenant can never enumerate another's people.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Set<String> SORT_FIELDS = Set.of("fullName", "email", "createdAt", "lastLoginAt");

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listOrganizationUsers(UserStatus status, UUID roleId, PageQuery query) {
        return PageResponse.from(userRepository.findOrganizationMembers(
                TenantContext.requireOrganizationId(), status, roleId, query.searchTerm(),
                query.toPageable(SORT_FIELDS, Sort.by(Sort.Direction.ASC, "fullName"))), this::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID userId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        if (!userRepository.isActiveMemberOf(userId, organizationId)) {
            // Same response as a genuinely missing user: existence itself is tenant-private.
            throw new ResourceNotFoundException("User", userId);
        }
        return toResponse(userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId)));
    }

    /**
     * Mini-profiles for a set of user ids, keyed by id. Published for the delivery
     * modules: a project member list, a board column or a backlog page all need names
     * and avatars for a handful of people at once, and calling {@link #getById} per row
     * turns one screen into a dozen queries.
     *
     * <p>Ids the caller is not entitled to see are simply absent from the result rather
     * than raising — a stale assignee reference should leave a blank avatar, not break
     * the whole board.</p>
     */
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> summariesFor(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        UUID organizationId = TenantContext.requireOrganizationId();
        Set<UUID> distinct = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return userRepository.findMembersByIds(organizationId, distinct).stream()
                .collect(Collectors.toMap(User::getId, UserService::toSummary));
    }

    /**
     * Asserts that a user is an active member of the caller's organization. Used before
     * assigning work or adding someone to a project, so an id from another tenant is
     * rejected instead of being written into a row that would then render as a blank.
     */
    @Transactional(readOnly = true)
    public void requireActiveMember(UUID userId) {
        if (!userRepository.isActiveMemberOf(userId, TenantContext.requireOrganizationId())) {
            throw new ResourceNotFoundException("User", userId);
        }
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (request.fullName() != null) user.setFullName(request.fullName().trim());
        if (request.avatarUrl() != null) user.setAvatarUrl(request.avatarUrl());
        if (request.jobTitle() != null) user.setJobTitle(request.jobTitle());
        if (request.timezone() != null) user.setTimezone(request.timezone());
        if (request.locale() != null) user.setLocale(request.locale());
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponse updateStatus(UUID userId, UserStatus status) {
        UUID organizationId = TenantContext.requireOrganizationId();
        if (!userRepository.isActiveMemberOf(userId, organizationId)) {
            throw new ResourceNotFoundException("User", userId);
        }
        if (TenantContext.requireUserId().equals(userId)) {
            throw new AccessDeniedAppException("You cannot change your own account status");
        }
        User user = userRepository.findById(userId).orElseThrow();
        user.setStatus(status);
        return toResponse(userRepository.save(user));
    }

    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getFullName(), user.getEmail(), user.getAvatarUrl());
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(),
                user.getJobTitle(), user.getTimezone(), user.getLocale(), user.getStatus().name(),
                user.isEmailVerified(), user.isMfaEnabled(), user.getLastLoginAt(), user.getCreatedAt());
    }
}
