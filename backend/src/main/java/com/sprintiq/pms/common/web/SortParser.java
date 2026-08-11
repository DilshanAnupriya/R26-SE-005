package com.sprintiq.pms.common.web;

import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses {@code field,direction} sort parameters against an allow-list.
 *
 * <p>The allow-list is the point: it keeps arbitrary property paths — and the injection
 * risk that comes with them — out of the generated JPQL.</p>
 *
 * <p>Spring binds a {@code List<String>} query parameter by splitting on commas, so the
 * documented {@code ?sort=name,desc} arrives here as {@code ["name", "desc"]} rather
 * than as the single token this originally assumed — which made the documented syntax
 * fail with "Cannot sort by 'desc'". Both shapes are accepted now: a bare
 * {@code asc}/{@code desc} is read as the direction for the property before it.</p>
 */
public final class SortParser {

    private static final String ASC = "asc";
    private static final String DESC = "desc";

    private SortParser() {}

    public static Sort parse(List<String> tokens, Set<String> allowedFields, Sort fallback) {
        if (tokens == null || tokens.isEmpty()) {
            return fallback;
        }

        List<Sort.Order> orders = new ArrayList<>();

        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;

            // A token may still carry both parts, if the client encoded the comma or a
            // caller built the list by hand. Splitting again makes both shapes converge.
            for (String part : token.split(",")) {
                String value = part.trim();
                if (value.isEmpty()) continue;

                if (isDirection(value)) {
                    applyDirection(orders, value);
                    continue;
                }

                if (!allowedFields.contains(value)) {
                    throw new ApplicationException(ErrorCode.VALIDATION_ERROR,
                            "Cannot sort by '%s'. Allowed: %s"
                                    .formatted(value, String.join(", ", allowedFields)));
                }
                // Ascending until a direction token says otherwise, matching Spring Data.
                orders.add(new Sort.Order(Sort.Direction.ASC, value));
            }
        }

        return orders.isEmpty() ? fallback : Sort.by(orders);
    }

    private static boolean isDirection(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return ASC.equals(lower) || DESC.equals(lower);
    }

    /**
     * Applies a direction to the property it follows.
     *
     * <p>A direction with nothing before it is ignored rather than rejected:
     * {@code ?sort=desc} alone is meaningless, and falling back to the endpoint's default
     * ordering is friendlier than a 422 about a parameter the caller almost certainly
     * built by string concatenation.</p>
     */
    private static void applyDirection(List<Sort.Order> orders, String direction) {
        if (orders.isEmpty()) {
            return;
        }
        int last = orders.size() - 1;
        Sort.Order previous = orders.get(last);
        orders.set(last, new Sort.Order(
                DESC.equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC,
                previous.getProperty()));
    }
}
