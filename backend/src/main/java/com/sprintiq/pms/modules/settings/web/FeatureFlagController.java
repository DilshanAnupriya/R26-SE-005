package com.sprintiq.pms.modules.settings.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.settings.application.FeatureFlagService;
import com.sprintiq.pms.modules.settings.web.dto.SettingsDtos.FeatureFlagResponse;
import com.sprintiq.pms.modules.settings.web.dto.SettingsDtos.UpdateFeatureFlagRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Capability switches per workspace.
 *
 * <p>Reading is open to anyone who can see the workspace — the UI has to know whether to
 * render the AI panels at all, and hiding that behind an administrative permission would
 * mean every member's page load failed on a 403. Writing needs {@code settings:manage}.</p>
 */
@Tag(name = "Feature flags", description = "Per-workspace capability switches")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/workspaces/{workspaceId}/feature-flags")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final FeatureFlagService featureFlags;

    @Operation(summary = "Effective flags for a workspace")
    @PreAuthorize("@perm.has('workspace:read')")
    @GetMapping
    public List<FeatureFlagResponse> list(@PathVariable UUID workspaceId) {
        return featureFlags.effectiveFlags(workspaceId).stream()
                .map(flag -> new FeatureFlagResponse(flag.flagKey(), label(flag.flagKey()),
                        flag.enabled(), flag.overridden(), flag.config()))
                .toList();
    }

    @Operation(summary = "Set a workspace override")
    @PreAuthorize("@perm.has('settings:manage')")
    @PatchMapping
    public FeatureFlagResponse set(@PathVariable UUID workspaceId,
                                   @Valid @RequestBody UpdateFeatureFlagRequest request) {
        featureFlags.set(workspaceId, request.flagKey(), request.enabled(), request.config());
        return new FeatureFlagResponse(request.flagKey(), label(request.flagKey()),
                request.enabled(), true, featureFlags.configOf(workspaceId, request.flagKey()));
    }

    @Operation(summary = "Remove a workspace override",
            description = "The workspace falls back to the organization default.")
    @PreAuthorize("@perm.has('settings:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{flagKey}")
    public void clear(@PathVariable UUID workspaceId, @PathVariable String flagKey) {
        featureFlags.clearOverride(workspaceId, flagKey);
    }

    /** Human label for the settings screen; the key itself stays the stable identifier. */
    private String label(String flagKey) {
        return switch (flagKey) {
            case FeatureFlagService.AI_STORY_POINTS -> "Story point suggestions";
            case FeatureFlagService.AI_RISK -> "Sprint risk assessment";
            case FeatureFlagService.AI_ALLOCATION -> "Assignment suggestions";
            case FeatureFlagService.AI_COST -> "Cost forecasting";
            default -> flagKey;
        };
    }
}
