package com.sprintiq.pms.modules.ai.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.ai.application.AiModelAdminService;
import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.AiModelResponse;
import com.sprintiq.pms.modules.ai.web.dto.AiDtos.UpdateAiModelRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The model registry, for administrators.
 *
 * <p>The registry is platform-wide — every tenant is served by the same deployed models
 * — so changing a row here changes it for everyone. That is why it sits behind
 * {@code ai:manage} and why the workspace parameter only affects the feature-flag state
 * shown alongside each model: switching a capability off for one workspace is a flag,
 * not a registry edit.</p>
 */
@Tag(name = "AI administration", description = "Model registry and rolling usage")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/ai/models")
@RequiredArgsConstructor
public class AiModelAdminController {

    private final AiModelAdminService service;

    @Operation(summary = "List models with their 30-day usage and acceptance")
    @PreAuthorize("@perm.hasAny('ai:manage','ai:predict')")
    @GetMapping
    public List<AiModelResponse> list(@RequestParam(required = false) UUID workspaceId) {
        return service.list(workspaceId);
    }

    @Operation(summary = "Get one model")
    @PreAuthorize("@perm.hasAny('ai:manage','ai:predict')")
    @GetMapping("/{code}")
    public AiModelResponse get(@PathVariable AiModelCode code,
                               @RequestParam(required = false) UUID workspaceId) {
        return service.get(code, workspaceId);
    }

    @Operation(summary = "Update a model's deployment settings",
            description = "Enable/disable, version, endpoint and latency budget. Platform-wide.")
    @PreAuthorize("@perm.has('ai:manage')")
    @PatchMapping("/{code}")
    public AiModelResponse update(@PathVariable AiModelCode code,
                                  @Valid @RequestBody UpdateAiModelRequest request,
                                  @RequestParam(required = false) UUID workspaceId) {
        return service.update(code, request, workspaceId);
    }
}
