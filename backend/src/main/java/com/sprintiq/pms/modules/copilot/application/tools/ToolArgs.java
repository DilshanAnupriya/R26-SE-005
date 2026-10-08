package com.sprintiq.pms.modules.copilot.application.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Argument parsing for tools, with errors phrased for the model to correct itself.
 *
 * <p>Models routinely pass a sprint's name where its id belongs. "sprintId must be a
 * UUID — call list_sprints to find it" gets a retry that works; a stack trace does not.</p>
 */
public final class ToolArgs {

    private ToolArgs() {}

    public static UUID requireUuid(JsonNode args, String field) {
        UUID value = optionalUuid(args, field);
        if (value == null) {
            throw new InvalidToolArgumentsException(field + " is required.");
        }
        return value;
    }

    public static UUID optionalUuid(JsonNode args, String field) {
        String raw = optionalString(args, field);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new InvalidToolArgumentsException(field + " must be a UUID, not \"" + raw
                    + "\". Use a list or search tool to find the id first.");
        }
    }

    public static List<UUID> requireUuidList(JsonNode args, String field) {
        JsonNode node = args.path(field);
        if (!node.isArray() || node.isEmpty()) {
            throw new InvalidToolArgumentsException(field + " must be a non-empty array of UUIDs.");
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : node) {
            try {
                ids.add(UUID.fromString(item.asText()));
            } catch (IllegalArgumentException ex) {
                throw new InvalidToolArgumentsException(field + " contains \"" + item.asText()
                        + "\", which is not a UUID.");
            }
        }
        return ids;
    }

    public static String optionalString(JsonNode args, String field) {
        JsonNode node = args.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    public static String requireString(JsonNode args, String field) {
        String value = optionalString(args, field);
        if (value == null) {
            throw new InvalidToolArgumentsException(field + " is required.");
        }
        return value;
    }

    public static Integer optionalInt(JsonNode args, String field, int min, int max) {
        JsonNode node = args.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.canConvertToInt()) {
            throw new InvalidToolArgumentsException(field + " must be a whole number.");
        }
        return Math.max(min, Math.min(max, node.asInt()));
    }

    public static boolean optionalBoolean(JsonNode args, String field) {
        return args.path(field).asBoolean(false);
    }

    public static <E extends Enum<E>> E optionalEnum(JsonNode args, String field, Class<E> type) {
        String raw = optionalString(args, field);
        if (raw == null) return null;
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidToolArgumentsException(field + " must be one of "
                    + java.util.Arrays.toString(type.getEnumConstants()) + ".");
        }
    }
}
