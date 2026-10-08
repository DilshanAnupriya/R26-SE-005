package com.sprintiq.pms.modules.copilot.application.llm;

/** A tool invocation the model asked for. Arguments arrive as the provider's raw JSON text. */
public record ToolCall(String id, String name, String argumentsJson) {}
