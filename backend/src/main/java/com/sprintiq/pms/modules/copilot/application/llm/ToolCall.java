package com.sprintiq.pms.modules.copilot.application.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool invocation the model asked for. Arguments arrive as the provider's raw JSON text.
 *
 * <p>{@code extraContent} is whatever the provider attached to the call beyond the OpenAI
 * fields, sent back untouched with the call. Gemini 3 puts the call's thought signature
 * there and rejects the next request with a 400 if the echoed call arrives without it.</p>
 */
public record ToolCall(String id, String name, String argumentsJson, JsonNode extraContent) {

    public ToolCall(String id, String name, String argumentsJson) {
        this(id, name, argumentsJson, null);
    }
}
