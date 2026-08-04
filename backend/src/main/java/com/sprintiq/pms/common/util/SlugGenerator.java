package com.sprintiq.pms.common.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** URL-safe slugs and project keys derived from human-entered names. */
public final class SlugGenerator {

    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("(^-+)|(-+$)");

    private SlugGenerator() {}

    public static String slugify(String input) {
        String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String slug = WHITESPACE.matcher(normalized.trim()).replaceAll("-");
        slug = NON_LATIN.matcher(slug).replaceAll("");
        slug = EDGE_DASHES.matcher(slug).replaceAll("");
        slug = slug.toLowerCase(Locale.ROOT);
        return slug.isBlank() ? "workspace" : slug.substring(0, Math.min(slug.length(), 60));
    }

    /** Finds the first free variant: {@code acme}, {@code acme-2}, {@code acme-3}, … */
    public static String uniqueSlug(String input, Predicate<String> exists) {
        String base = slugify(input);
        String candidate = base;
        int suffix = 2;
        while (exists.test(candidate)) {
            candidate = base + "-" + suffix++;
            if (suffix > 1000) {
                throw new IllegalStateException("Unable to allocate a unique slug for " + input);
            }
        }
        return candidate;
    }

    /** Project/workspace keys: {@code "Mobile Banking App"} -> {@code "MBA"}. */
    public static String toKey(String name, int maxLength) {
        if (name == null || name.isBlank()) return "PRJ";
        String[] words = name.trim().split("[\\s_-]+");
        StringBuilder key = new StringBuilder();
        if (words.length == 1) {
            key.append(words[0].replaceAll("[^A-Za-z0-9]", ""));
        } else {
            for (String word : words) {
                if (!word.isBlank() && Character.isLetterOrDigit(word.charAt(0))) {
                    key.append(word.charAt(0));
                }
            }
        }
        String result = key.toString().toUpperCase(Locale.ROOT);
        if (result.isBlank()) result = "PRJ";
        return result.substring(0, Math.min(result.length(), maxLength));
    }
}
