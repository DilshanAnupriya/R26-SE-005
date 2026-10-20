package com.sprintiq.pms.modules.copilot.application.tools;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface ToolHandler {
    Object handle(JsonNode args, ToolContext context);
}
