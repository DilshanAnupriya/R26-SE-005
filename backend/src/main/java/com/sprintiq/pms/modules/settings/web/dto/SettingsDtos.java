package com.sprintiq.pms.modules.settings.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public final class SettingsDtos {

    private SettingsDtos() {
    }

    @Schema(name = "FeatureFlagResponse",
            description = "Effective state after workspace override and organization default")
    public record FeatureFlagResponse(String flagKey,
                                      String label,
                                      boolean enabled,
                                      /** True when a workspace-level row overrides the org default. */
                                      boolean overridden,
                                      Map<String, Object> config) {}

    @Schema(name = "UpdateFeatureFlagRequest")
    public record UpdateFeatureFlagRequest(@NotBlank @Size(max = 80) String flagKey,
                                           @NotNull Boolean enabled,
                                           Map<String, Object> config) {}
}
