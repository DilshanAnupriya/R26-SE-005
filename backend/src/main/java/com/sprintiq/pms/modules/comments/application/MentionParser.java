package com.sprintiq.pms.modules.comments.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts mentioned user ids from comment text.
 *
 * <p>The wire format is {@code @[Priya Raman](3f2a…)} — the display name for humans, the
 * id for the machine. Parsing bare {@code @priya} would mean guessing which Priya, and
 * guessing wrong sends someone else's work notification to the wrong person. The client's
 * mention picker already knows the id; making it send one is free.</p>
 *
 * <p>The id is what counts. A renamed user's old comments keep working, and editing the
 * display text inside the brackets cannot redirect a notification.</p>
 */
public final class MentionParser {

    private static final Pattern MENTION = Pattern.compile(
            "@\\[[^\\]]{1,150}]\\(([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\)");

    private MentionParser() {
    }

    /** Mentioned ids in the order they appear, de-duplicated. */
    public static List<UUID> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        LinkedHashSet<UUID> found = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(body);
        while (matcher.find()) {
            try {
                found.add(UUID.fromString(matcher.group(1)));
            } catch (IllegalArgumentException ignored) {
                // The regex already constrains the shape; a value that still fails to
                // parse is malformed input, and a malformed mention is simply not one.
            }
        }
        return List.copyOf(found);
    }

    /**
     * Replaces mention markup with the plain display name, for notification bodies and
     * anywhere else the raw syntax would leak into a sentence a person reads.
     */
    public static String toPlainText(String body) {
        if (body == null) {
            return null;
        }
        return body.replaceAll("@\\[([^\\]]{1,150})]\\([0-9a-fA-F-]{36}\\)", "@$1");
    }
}
