package com.sprintiq.pms.common.util;

import java.util.List;

/**
 * Lexicographic ordering keys for the backlog.
 *
 * <p>Reordering a backlog by storing an integer position means every row after the
 * moved item has to be rewritten. Instead each item holds a string rank and the
 * backlog is simply {@code ORDER BY rank}; moving an item only rewrites that one
 * row, because a new string can always be generated strictly between its two
 * neighbours.</p>
 *
 * <p>Ranks use the alphabet {@code 0-9a-z} (base 36). {@link #between} finds the
 * shortest string that sorts strictly after {@code before} and strictly before
 * {@code after}; when the two are adjacent it lengthens the prefix rather than
 * failing, so a backlog can be reordered indefinitely without a rebalance pass.</p>
 */
public final class LexoRank {

    private static final char MIN_CHAR = '0';
    private static final char MAX_CHAR = 'z';
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    /** Rank handed to the first item in an empty list. Mid-alphabet, so both ends stay open. */
    public static final String INITIAL = "n";

    private LexoRank() {}

    /**
     * A rank strictly between the two neighbours. Either bound may be {@code null}
     * to mean "no neighbour on that side" — i.e. moving to the head or the tail.
     *
     * @throws IllegalArgumentException if {@code before} does not sort before {@code after}
     */
    public static String between(String before, String after) {
        String low = normalize(before, String.valueOf(MIN_CHAR));
        String high = normalize(after, null);

        if (high == null) {
            return incrementLast(low);
        }
        if (low.compareTo(high) >= 0) {
            throw new IllegalArgumentException(
                    "Cannot rank between '%s' and '%s': bounds are out of order".formatted(before, after));
        }
        return midpoint(low, high);
    }

    /** Rank that sorts before every existing rank — used when moving an item to the top. */
    public static String before(String first) {
        return between(null, first);
    }

    /** Rank that sorts after every existing rank — used when appending to the backlog. */
    public static String after(String last) {
        return between(last, null);
    }

    /**
     * Ranks for a contiguous run of items dropped between two neighbours, in order.
     * Generating them in one pass keeps a bulk reorder to a single rank computation
     * per item instead of re-reading the list between moves.
     */
    public static List<String> sequence(String before, String after, int count) {
        if (count <= 0) {
            return List.of();
        }
        String[] ranks = new String[count];
        String cursor = before;
        for (int i = 0; i < count; i++) {
            cursor = between(cursor, after);
            ranks[i] = cursor;
        }
        return List.of(ranks);
    }

    // ------------------------------------------------------------------ internals

    private static String normalize(String rank, String fallback) {
        if (rank == null || rank.isBlank()) {
            return fallback;
        }
        String trimmed = rank.trim().toLowerCase();
        for (int i = 0; i < trimmed.length(); i++) {
            if (ALPHABET.indexOf(trimmed.charAt(i)) < 0) {
                throw new IllegalArgumentException("Rank '%s' contains a character outside 0-9a-z".formatted(rank));
            }
        }
        return trimmed;
    }

    /**
     * Appends past the tail. Bumping the final character keeps ranks short; once it
     * is already at the top of the alphabet we extend instead, which still sorts after.
     */
    private static String incrementLast(String rank) {
        int lastIndex = rank.length() - 1;
        char last = rank.charAt(lastIndex);
        if (last < MAX_CHAR) {
            return rank.substring(0, lastIndex) + ALPHABET.charAt(ALPHABET.indexOf(last) + 1);
        }
        return rank + INITIAL;
    }

    /**
     * Shortest string strictly between {@code low} and {@code high}. Walks the shared
     * prefix, then either splits the gap at the first differing digit or — when the
     * digits are adjacent — descends into {@code low} and appends, which is what keeps
     * repeated inserts at the same position from ever running out of room.
     */
    private static String midpoint(String low, String high) {
        StringBuilder result = new StringBuilder();
        int position = 0;

        while (true) {
            char lowChar = position < low.length() ? low.charAt(position) : MIN_CHAR;
            char highChar = position < high.length() ? high.charAt(position) : (char) (MAX_CHAR + 1);

            if (lowChar == highChar) {
                result.append(lowChar);
                position++;
                continue;
            }

            int lowValue = ALPHABET.indexOf(lowChar);
            int highValue = highChar > MAX_CHAR ? BASE : ALPHABET.indexOf(highChar);

            if (highValue - lowValue > 1) {
                result.append(ALPHABET.charAt((lowValue + highValue) / 2));
                return result.toString();
            }

            // Adjacent digits: keep the low digit and find room in the remainder of `low`.
            result.append(lowChar);
            String lowRemainder = position + 1 < low.length() ? low.substring(position + 1) : "";
            result.append(afterRemainder(lowRemainder));
            return result.toString();
        }
    }

    /** Shortest suffix that sorts strictly after {@code remainder} within the same prefix. */
    private static String afterRemainder(String remainder) {
        StringBuilder suffix = new StringBuilder();
        int position = 0;
        while (true) {
            char current = position < remainder.length() ? remainder.charAt(position) : MIN_CHAR;
            int value = ALPHABET.indexOf(current);
            if (value < BASE - 1) {
                suffix.append(ALPHABET.charAt((value + BASE) / 2));
                return suffix.toString();
            }
            suffix.append(current);
            position++;
        }
    }
}
