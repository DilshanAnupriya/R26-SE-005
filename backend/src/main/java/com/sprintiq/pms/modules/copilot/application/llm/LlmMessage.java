package com.sprintiq.pms.modules.copilot.application.llm;

import java.util.List;

/**
 * One message in a provider conversation, independent of any provider's wire shape.
 *
 * <p>{@code toolCalls} is set only on an assistant message that asked for tools, and
 * {@code toolCallId} only on a {@code tool} message answering one of them.</p>
 */
public record LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, List.of(), null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, List.of(), null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content, List.of(), null);
    }

    public static LlmMessage assistantToolCalls(String content, List<ToolCall> calls) {
        return new LlmMessage("assistant", content, List.copyOf(calls), null);
    }

    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage("tool", content, List.of(), toolCallId);
    }
}
