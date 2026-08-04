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

import java.util.Set;
import java.util.UUID;

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

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(),
                user.getJobTitle(), user.getTimezone(), user.getLocale(), user.getStatus().name(),
                user.isEmailVerified(), user.isMfaEnabled(), user.getLastLoginAt(), user.getCreatedAt());
    }
}
