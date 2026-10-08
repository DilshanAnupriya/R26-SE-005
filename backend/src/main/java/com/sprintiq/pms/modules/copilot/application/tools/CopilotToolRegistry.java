package com.sprintiq.pms.modules.copilot.application.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.modules.copilot.application.llm.ToolSpec;
import com.sprintiq.pms.modules.copilot.domain.ToolStep;
import com.sprintiq.pms.security.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every Copilot tool, and the one place they are permission-checked and run.
 *
 * <p>Failures come back as {@code {"error": ...}} rather than exceptions. A missing
 * sprint or a model that is switched off is information the assistant should relay —
 * "that sprint has not been scored yet" — not a reason to lose the whole answer.</p>
 */
@Slf4j
@Component
public class CopilotToolRegistry {

    /** Roughly 2k tokens. Enough for a sprint summary; not enough to drown the context. */
    public static final int MAX_RESULT_CHARS = 8000;

    private final Map<String, CopilotTool> tools = new LinkedHashMap<>();
    private final PermissionService perm;
    private final ObjectMapper mapper;

    public CopilotToolRegistry(List<CopilotTool> tools, PermissionService perm, ObjectMapper mapper) {
        tools.forEach(tool -> {
            if (this.tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate Copilot tool name: " + tool.name());
            }
        });
        this.perm = perm;
        this.mapper = mapper;
    }

    /**
     * The tools this user may call, as offered to the model.
     *
     * <p>Filtering here as well as at execution keeps the model from planning around a
     * tool it will only be refused — it cannot suggest "I'll assign that for you" to
     * someone who cannot assign.</p>
     */
    public List<ToolSpec> permittedSpecs() {
        return tools.values().stream()
                .filter(tool -> perm.has(tool.requiredPermission()))
                .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    public ToolOutcome run(String name, String argumentsJson, ToolContext context) {
        CopilotTool tool = tools.get(name);
        if (tool == null) {
            return failure(name, name, "Unknown tool: " + name);
        }
        if (!perm.has(tool.requiredPermission())) {
            return failure(name, tool.label(),
                    "The user does not have permission to use " + name + ".");
        }

        JsonNode args;
        try {
            args = argumentsJson == null || argumentsJson.isBlank()
                    ? mapper.createObjectNode() : mapper.readTree(argumentsJson);
            if (!args.isObject()) {
                return failure(name, tool.label(), "Arguments must be a JSON object.");
            }
        } catch (JsonProcessingException ex) {
            return failure(name, tool.label(), "Arguments were not valid JSON.");
        }

        try {
            Object result = tool.execute(args, context);
            return new ToolOutcome(truncate(mapper.writeValueAsString(result)),
                    new ToolStep(name, true, tool.label()));
        } catch (InvalidToolArgumentsException | ApplicationException ex) {
            return failure(name, tool.label(), ex.getMessage());
        } catch (JsonProcessingException ex) {
            log.warn("Copilot tool {} produced an unserialisable result", name, ex);
            return failure(name, tool.label(), "The tool's result could not be read.");
        } catch (RuntimeException ex) {
            log.warn("Copilot tool {} failed", name, ex);
            return failure(name, tool.label(), "The tool failed unexpectedly.");
        }
    }

    private ToolOutcome failure(String name, String label, String message) {
        String json;
        try {
            json = mapper.writeValueAsString(Map.of("error", message));
        } catch (JsonProcessingException ex) {
            json = "{\"error\":\"tool failed\"}";
        }
        return new ToolOutcome(json, new ToolStep(name, false, label + " — " + message));
    }

    private static String truncate(String json) {
        if (json.length() <= MAX_RESULT_CHARS) return json;
        // No longer valid JSON once cut, which is fine: the reader is a language model,
        // and it needs to know the list goes on more than it needs a closing brace.
        return json.substring(0, MAX_RESULT_CHARS)
                + " … [truncated: result was " + json.length() + " characters; narrow the query]";
    }
}
