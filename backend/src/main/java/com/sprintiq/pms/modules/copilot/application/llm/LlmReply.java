package com.sprintiq.pms.modules.copilot.application.llm;

import java.util.List;

/** What the model said back: text, tool requests, or both. */
public record LlmReply(String content, List<ToolCall> toolCalls, String model) {

    public LlmReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
