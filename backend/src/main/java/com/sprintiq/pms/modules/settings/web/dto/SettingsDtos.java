package com.sprintiq.pms.modules.settings.web.dto;

import com.sprintiq.pms.modules.settings.domain.IntegrationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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

    // ------------------------------------------------------- workspace settings

    @Schema(name = "UpdateWorkspaceSettingsRequest",
            description = "Only the keys you send are changed; everything else keeps its value.")
    public record UpdateWorkspaceSettingsRequest(@NotNull Map<String, Object> settings) {}

    // ------------------------------------------------------------- integrations

    @Schema(name = "IntegrationResponse",
            description = "secretRef is never returned — only whether a credential is configured")
    public record IntegrationResponse(UUID id,
                                      UUID workspaceId,
                                      String provider,
                                      String displayName,
                                      Map<String, Object> config,
                                      boolean credentialConfigured,
                                      IntegrationStatus status,
                                      Instant lastSyncAt,
                                      Instant createdAt) {}

    @Schema(name = "CreateIntegrationRequest")
    public record CreateIntegrationRequest(UUID workspaceId,
                                           @NotBlank @Size(max = 48) String provider,
                                           @Size(max = 120) String displayName,
                                           Map<String, Object> config,
                                           @Schema(description = "A path into the secret store, "
                                                   + "never the credential itself")
                                           @Size(max = 255) String secretRef) {}

    @Schema(name = "UpdateIntegrationRequest")
    public record UpdateIntegrationRequest(@Size(max = 120) String displayName,
                                           Map<String, Object> config,
                                           @Size(max = 255) String secretRef,
                                           IntegrationStatus status) {}
}
