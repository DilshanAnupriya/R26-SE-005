package com.sprintiq.pms.modules.copilot.application.llm;

import java.util.Map;

/** A tool as offered to the model: a name, what it is for, and a JSON-schema of its arguments. */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {}
