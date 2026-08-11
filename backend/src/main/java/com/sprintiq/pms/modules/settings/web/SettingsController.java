package com.sprintiq.pms.modules.settings.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.settings.application.WorkspaceSettingsService;
import com.sprintiq.pms.modules.settings.web.dto.SettingsDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Settings", description = "Workspace configuration and integrations")
@RestController
@RequestMapping(ApiConstants.API_V1)
@RequiredArgsConstructor
public class SettingsController {

    private final WorkspaceSettingsService settingsService;

    @Operation(summary = "Workspace settings",
            description = "Every known key, with defaults filled in for anything never set.")
    @PreAuthorize("@perm.has('settings:read')")
    @GetMapping("/workspaces/{workspaceId}/settings")
    public Map<String, Object> settings(@PathVariable UUID workspaceId) {
        return settingsService.settingsOf(workspaceId);
    }

    @Operation(summary = "Update workspace settings",
            description = "Partial: only the keys in the request are changed.")
    @PreAuthorize("@perm.has('settings:manage')")
    @PatchMapping("/workspaces/{workspaceId}/settings")
    public Map<String, Object> updateSettings(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWorkspaceSettingsRequest request) {
        return settingsService.update(workspaceId, request.settings());
    }

    @Operation(summary = "Configured integrations")
    @PreAuthorize("@perm.has('settings:read')")
    @GetMapping("/integrations")
    public List<IntegrationResponse> integrations(@RequestParam(required = false) UUID workspaceId) {
        return settingsService.integrationsOf(workspaceId);
    }

    @Operation(summary = "Add an integration",
            description = "Credentials are referenced by secret-store path, never stored here.")
    @PreAuthorize("@perm.has('settings:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/integrations")
    public IntegrationResponse createIntegration(
            @Valid @RequestBody CreateIntegrationRequest request) {
        return settingsService.create(request);
    }

    @Operation(summary = "Update an integration")
    @PreAuthorize("@perm.has('settings:manage')")
    @PatchMapping("/integrations/{integrationId}")
    public IntegrationResponse updateIntegration(
            @PathVariable UUID integrationId,
            @Valid @RequestBody UpdateIntegrationRequest request) {
        return settingsService.update(integrationId, request);
    }

    @Operation(summary = "Remove an integration")
    @PreAuthorize("@perm.has('settings:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/integrations/{integrationId}")
    public void deleteIntegration(@PathVariable UUID integrationId) {
        settingsService.delete(integrationId);
    }
}
