package com.sprintiq.pms.modules.users.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.modules.users.web.dto.UserDtos.*;
import com.sprintiq.pms.security.AppPrincipal;
import com.sprintiq.pms.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Users", description = "Directory and profile management")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "List users in the active organization")
    @PreAuthorize("@perm.has('member:read')")
    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(required = false) UserStatus status,
                                           @RequestParam(required = false) UUID roleId,
                                           @Valid PageQuery query) {
        return userService.listOrganizationUsers(status, roleId, query);
    }

    @Operation(summary = "Current user profile")
    @GetMapping("/me")
    public UserResponse me(@CurrentUser AppPrincipal principal) {
        return userService.getById(principal.userId());
    }

    @Operation(summary = "Update your own profile")
    @PatchMapping("/me")
    public UserResponse updateMe(@CurrentUser AppPrincipal principal,
                                 @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(principal.userId(), request);
    }

    @Operation(summary = "Get a user")
    @PreAuthorize("@perm.has('member:read')")
    @GetMapping("/{userId}")
    public UserResponse get(@PathVariable UUID userId) {
        return userService.getById(userId);
    }

    @Operation(summary = "Activate or suspend a user")
    @PreAuthorize("@perm.has('member:update')")
    @PatchMapping("/{userId}/status")
    public UserResponse updateStatus(@PathVariable UUID userId,
                                     @Valid @RequestBody UpdateUserStatusRequest request) {
        return userService.updateStatus(userId, UserStatus.valueOf(request.status()));
    }
}
