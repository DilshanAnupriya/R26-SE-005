package com.sprintiq.pms.modules.copilot.application.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Something the Copilot can call.
 *
 * <p>Every tool runs as the person asking, through the same application services the
 * controllers use, so it can never see or change more than that person could by
 * clicking. {@link #requiredPermission()} is the controller's {@code @PreAuthorize}
 * restated: services do not check it themselves, so a tool must.</p>
 */
public interface CopilotTool {

    /** snake_case, as the model will call it. */
    String name();

    /** Short, for the "used" line under an answer — "Read sprint risk (M2)". */
    String label();

    /** For the model: what it returns and when to use it. Caveats belong here. */
    String description();

    /** JSON schema of the arguments object. */
    Map<String, Object> parameters();

    String requiredPermission();

    /** The result, serialised to JSON by the registry. May throw; the registry reports it. */
    Object execute(JsonNode args, ToolContext context);
}
