package com.sprintiq.pms.modules.users.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {}

    @Schema(name = "UserResponse")
    public record UserResponse(UUID id, String email, String fullName, String avatarUrl, String jobTitle,
                               String timezone, String locale, String status, boolean emailVerified,
                               boolean mfaEnabled, Instant lastLoginAt, Instant createdAt) {}

    @Schema(name = "UpdateProfileRequest")
    public record UpdateProfileRequest(
            @Size(min = 2, max = 150) String fullName,
            @Size(max = 512) String avatarUrl,
            @Size(max = 120) String jobTitle,
            @Size(max = 64) String timezone,
            @Size(max = 16) String locale) {}

    @Schema(name = "UpdateUserStatusRequest")
    public record UpdateUserStatusRequest(@Schema(allowableValues = {"ACTIVE", "SUSPENDED"}) String status) {}
}
