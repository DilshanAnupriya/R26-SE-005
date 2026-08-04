package com.sprintiq.pms.common.web;

import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Parses {@code field,direction} tokens against an allow-list of sortable properties. */
public final class SortParser {

    private SortParser() {}

    public static Sort parse(List<String> tokens, Set<String> allowedFields, Sort fallback) {
        if (tokens == null || tokens.isEmpty()) {
            return fallback;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            String[] parts = token.split(",");
            String property = parts[0].trim();
            if (!allowedFields.contains(property)) {
                throw new ApplicationException(ErrorCode.VALIDATION_ERROR,
                        "Cannot sort by '%s'. Allowed: %s".formatted(property, String.join(", ", allowedFields)));
            }
            Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                    ? Sort.Direction.DESC : Sort.Direction.ASC;
            orders.add(new Sort.Order(direction, property));
        }
        return orders.isEmpty() ? fallback : Sort.by(orders);
    }
}
