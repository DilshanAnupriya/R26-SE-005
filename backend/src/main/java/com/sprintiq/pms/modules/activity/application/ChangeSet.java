package com.sprintiq.pms.modules.activity.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Accumulates the field-level diff of a PATCH so a service can apply changes and
 * describe them in one pass.
 *
 * <p>Update handlers all share the same shape — "if the request carries a value and
 * it differs from what is stored, set it and remember that you did". Doing that
 * inline produces either a second manual diff (which drifts from the setters) or no
 * history at all. {@link #apply} collapses it into a single call:</p>
 *
 * <pre>{@code
 * ChangeSet changes = new ChangeSet();
 * changes.apply("title", story.getTitle(), request.title(), story::setTitle);
 * changes.apply("priority", story.getPriority(), request.priority(), story::setPriority);
 * if (changes.isEmpty()) return toResponse(story);
 * }</pre>
 *
 * <p>Not thread-safe, and not meant to be — instances live inside one service call.</p>
 */
public final class ChangeSet {

    /** One recorded difference. Values are rendered as text for the history table. */
    public record FieldChange(String field, String oldValue, String newValue) {}

    private final List<FieldChange> changes = new ArrayList<>();

    /**
     * Sets {@code newValue} through {@code setter} when the request supplied one and it
     * actually differs, recording the difference. A {@code null} {@code newValue} means
     * "field absent from the PATCH" and is ignored — use {@link #applyNullable} for
     * fields the client is allowed to clear.
     */
    public <T> ChangeSet apply(String field, T currentValue, T newValue, java.util.function.Consumer<T> setter) {
        if (newValue == null || Objects.equals(currentValue, newValue)) {
            return this;
        }
        setter.accept(newValue);
        return record(field, currentValue, newValue);
    }

    /**
     * Variant for fields that can legitimately be set back to empty. The caller decides
     * whether the client meant "absent" or "clear it" — pass {@code present=false} for
     * absent, and {@code newValue=null} with {@code present=true} to clear.
     */
    public <T> ChangeSet applyNullable(String field, T currentValue, T newValue, boolean present,
                                       java.util.function.Consumer<T> setter) {
        if (!present || Objects.equals(currentValue, newValue)) {
            return this;
        }
        setter.accept(newValue);
        return record(field, currentValue, newValue);
    }

    /** Records a change the caller applied itself (state machines, derived fields). */
    public ChangeSet record(String field, Object oldValue, Object newValue) {
        changes.add(new FieldChange(field, render(oldValue), render(newValue)));
        return this;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public List<FieldChange> changes() {
        return List.copyOf(changes);
    }

    /** Flat {@code field -> value} view of the "before" side, for the audit trail. */
    public Map<String, Object> beforeState() {
        Map<String, Object> state = new HashMap<>();
        changes.forEach(change -> state.put(change.field(), change.oldValue()));
        return state;
    }

    /** Flat {@code field -> value} view of the "after" side, for the audit trail. */
    public Map<String, Object> afterState() {
        Map<String, Object> state = new HashMap<>();
        changes.forEach(change -> state.put(change.field(), change.newValue()));
        return state;
    }

    private static String render(Object value) {
        if (value == null) return null;
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        String text = String.valueOf(value);
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }
}
