package com.sprintiq.pms.modules.copilot.application.tools;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small builders for the JSON-schema fragments tools declare. */
public final class ToolSchemas {

    private ToolSchemas() {}

    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> uuid(String description) {
        return Map.of("type", "string", "description", description + " (UUID)");
    }

    public static Map<String, Object> uuidArray(String description) {
        return Map.of("type", "array", "description", description + " (UUIDs)",
                "items", Map.of("type", "string"));
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> enumOf(String description, Enum<?>... values) {
        return Map.of("type", "string", "description", description,
                "enum", Arrays.stream(values).map(Enum::name).toList());
    }
}
