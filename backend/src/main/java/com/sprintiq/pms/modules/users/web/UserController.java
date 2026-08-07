package com.sprintiq.pms.modules.users.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.users.application.DeveloperProfileService;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "Users", description = "Directory and profile management")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final DeveloperProfileService developerProfileService;

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

    // ------------------------------------------------------- developer profile

    @Operation(summary = "Developer profile and delivery metrics",
            description = "Declared attributes plus the metrics derived from completed work. "
                    + "These are the Model 3 features; the profile explains a ranking.")
    @PreAuthorize("@perm.has('member:read')")
    @GetMapping("/{userId}/developer-profile")
    public DeveloperProfileResponse developerProfile(@PathVariable UUID userId) {
        return developerProfileService.get(userId);
    }

    @Operation(summary = "Update declared profile attributes",
            description = "Seniority, experience, rate, capacity and availability. Derived metrics "
                    + "cannot be set — they are measured, not asserted.")
    @PreAuthorize("@perm.hasAny('member:update','settings:manage')")
    @PutMapping("/{userId}/developer-profile")
    public DeveloperProfileResponse updateDeveloperProfile(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateDeveloperProfileRequest request) {
        return developerProfileService.update(userId, request);
    }

    @Operation(summary = "A user's skills and proficiencies")
    @PreAuthorize("@perm.has('member:read')")
    @GetMapping("/{userId}/skills")
    public List<UserSkillResponse> skills(@PathVariable UUID userId) {
        return developerProfileService.skillsOf(userId);
    }

    @Operation(summary = "Replace a user's skills",
            description = "Whole-set replacement so removals are expressible. Unknown skill names "
                    + "are created in the organization's catalogue.")
    @PreAuthorize("@perm.hasAny('member:update','settings:manage')")
    @PutMapping("/{userId}/skills")
    public List<UserSkillResponse> replaceSkills(@PathVariable UUID userId,
                                                 @Valid @RequestBody UpdateUserSkillsRequest request) {
        return developerProfileService.replaceSkills(userId, request);
    }

    @Operation(summary = "The organization's skill catalogue")
    @PreAuthorize("@perm.has('member:read')")
    @GetMapping("/skills/catalogue")
    public List<SkillResponse> skillCatalogue() {
        return developerProfileService.catalogue();
    }
}
