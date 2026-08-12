package com.sprintiq.pms.modules.users.web.dto;

import com.sprintiq.pms.modules.users.domain.Seniority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    // ------------------------------------------------------- developer profile

    /**
     * Declared attributes plus measured delivery metrics.
     *
     * <p>{@code measured} says whether there is enough history for the derived figures to
     * be worth reading. Without it a brand-new joiner's default 0.500 consistency looks
     * like a finding rather than a placeholder.</p>
     */
    @Schema(name = "DeveloperProfileResponse")
    public record DeveloperProfileResponse(UUID userId,
                                           Seniority seniority,
                                           BigDecimal yearsExperience,
                                           BigDecimal hourlyRate,
                                           BigDecimal weeklyCapacityHours,
                                           BigDecimal availabilityRate,
                                           BigDecimal avgVelocity,
                                           BigDecimal consistencyScore,
                                           BigDecimal learningRate,
                                           BigDecimal defectRate,
                                           BigDecimal reopenRate,
                                           BigDecimal blockedFrequency,
                                           BigDecimal completionVariance,
                                           int tasksCompleted,
                                           boolean measured,
                                           Instant lastComputedAt) {}

    @Schema(name = "UpdateDeveloperProfileRequest",
            description = "Declared attributes only. Measured metrics are derived from delivery "
                    + "history and cannot be set.")
    public record UpdateDeveloperProfileRequest(
            Seniority seniority,
            @DecimalMin("0.0") @DecimalMax("70.0") BigDecimal yearsExperience,
            @DecimalMin("0.0") BigDecimal hourlyRate,
            @DecimalMin("0.0") @DecimalMax("168.0") BigDecimal weeklyCapacityHours,
            @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal availabilityRate) {}

    @Schema(name = "SkillResponse")
    public record SkillResponse(UUID id, String name, String category) {}

    @Schema(name = "UserSkillResponse")
    public record UserSkillResponse(UUID skillId, String skillName, String category, short proficiency) {}

    @Schema(name = "UserSkillEntry",
            description = "Identify the skill by id, or by name to create it on first use.")
    public record UserSkillEntry(UUID skillId,
                                 @Size(max = 80) String skillName,
                                 @Min(1) @Max(5) Short proficiency) {

        @jakarta.validation.constraints.AssertTrue(message = "Provide either skillId or skillName")
        public boolean isIdentified() {
            return skillId != null || (skillName != null && !skillName.isBlank());
        }
    }

    @Schema(name = "UpdateUserSkillsRequest", description = "Replaces the whole skill set")
    public record UpdateUserSkillsRequest(@NotNull @Size(max = 50) List<@Valid UserSkillEntry> skills) {}
}
