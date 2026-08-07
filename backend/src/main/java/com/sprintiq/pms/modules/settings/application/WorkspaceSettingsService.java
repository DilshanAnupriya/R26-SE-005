package com.sprintiq.pms.modules.settings.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.settings.domain.*;
import com.sprintiq.pms.modules.settings.web.dto.SettingsDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Workspace configuration and integrations.
 *
 * <p>Settings are upserted by key so a client can send only what changed. A
 * whole-document PUT would make two people editing different preferences on the same
 * screen overwrite each other, and settings screens are exactly where that happens.</p>
 */
@Service
@RequiredArgsConstructor
public class WorkspaceSettingsService {

    /** Known keys with their defaults, so an untouched workspace still renders. */
    public static final Map<String, Object> DEFAULTS = Map.of(
            "sprint.default_length_days", 14,
            "sprint.working_days", List.of("MON", "TUE", "WED", "THU", "FRI"),
            "estimation.scale", "FIBONACCI",
            "board.wip_limits_enforced", false,
            "backlog.require_estimate_before_sprint", false,
            "notifications.digest_hour_utc", 8);

    private final WorkspaceSettingRepository settings;
    private final IntegrationRepository integrations;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> settingsOf(UUID workspaceId) {
        Map<String, Object> resolved = new LinkedHashMap<>(DEFAULTS);
        settings.findByWorkspaceId(workspaceId).forEach(setting ->
                resolved.put(setting.getSettingKey(),
                        objectMapper.convertValue(setting.getSettingValue(), Object.class)));
        return resolved;
    }

    @Transactional
    public Map<String, Object> update(UUID workspaceId, Map<String, Object> changes) {
        changes.forEach((key, value) -> {
            WorkspaceSetting setting = settings.findByWorkspaceIdAndSettingKey(workspaceId, key)
                    .orElseGet(() -> WorkspaceSetting.builder()
                            .workspaceId(workspaceId).settingKey(key).build());
            setting.setSettingValue(objectMapper.<JsonNode>valueToTree(value));
            settings.save(setting);
        });
        return settingsOf(workspaceId);
    }

    // ---------------------------------------------------------------- integrations

    @Transactional(readOnly = true)
    public List<IntegrationResponse> integrationsOf(UUID workspaceId) {
        List<Integration> found = workspaceId == null
                ? integrations.findAllByOrderByProviderAsc()
                : integrations.findByWorkspaceId(workspaceId);
        return found.stream().map(this::toResponse).toList();
    }

    @Transactional
    public IntegrationResponse create(CreateIntegrationRequest request) {
        Integration integration = Integration.builder()
                .workspaceId(request.workspaceId())
                .provider(request.provider())
                .displayName(request.displayName())
                .config(request.config() == null ? new HashMap<>() : new HashMap<>(request.config()))
                .secretRef(request.secretRef())
                .status(IntegrationStatus.INACTIVE)
                .build();
        return toResponse(integrations.save(integration));
    }

    @Transactional
    public IntegrationResponse update(UUID integrationId, UpdateIntegrationRequest request) {
        Integration integration = integrations.findById(integrationId)
                .orElseThrow(() -> new ResourceNotFoundException("Integration", integrationId));
        if (request.displayName() != null) integration.setDisplayName(request.displayName());
        if (request.config() != null) integration.setConfig(new HashMap<>(request.config()));
        if (request.secretRef() != null) integration.setSecretRef(request.secretRef());
        if (request.status() != null) {
            integration.setStatus(request.status());
            if (request.status() == IntegrationStatus.ACTIVE) {
                integration.setLastSyncAt(Instant.now());
            }
        }
        return toResponse(integrations.save(integration));
    }

    @Transactional
    public void delete(UUID integrationId) {
        integrations.findById(integrationId).ifPresent(integrations::delete);
    }

    /**
     * Never returns {@code secretRef} — only whether one is configured.
     *
     * <p>The reference is not itself a credential, but it names a path in the secret
     * store, and handing that to every member who can read settings is a free map for
     * anyone who later finds a way in.</p>
     */
    private IntegrationResponse toResponse(Integration integration) {
        return new IntegrationResponse(integration.getId(), integration.getWorkspaceId(),
                integration.getProvider(), integration.getDisplayName(), integration.getConfig(),
                integration.getSecretRef() != null && !integration.getSecretRef().isBlank(),
                integration.getStatus(), integration.getLastSyncAt(), integration.getCreatedAt());
    }
}
