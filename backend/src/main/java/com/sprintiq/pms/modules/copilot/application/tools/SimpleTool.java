package com.sprintiq.pms.modules.copilot.application.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.Map;

/** A tool defined by its metadata and a lambda — what every Copilot tool actually is. */
@Builder
public record SimpleTool(@NonNull String name,
                         @NonNull String label,
                         @NonNull String description,
                         @NonNull Map<String, Object> parameters,
                         @NonNull String requiredPermission,
                         @NonNull ToolHandler handler) implements CopilotTool {

    @Override
    public Object execute(JsonNode args, ToolContext context) {
        return handler.handle(args, context);
    }
}
