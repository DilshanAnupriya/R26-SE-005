package com.sprintiq.pms.modules.ai.application;

import java.math.BigDecimal;
import java.util.*;

/**
 * The features handed to a model, in insertion order, with typed reads.
 *
 * <p>Deliberately a map rather than a record per model. This exact structure is three
 * things at once: the JSON body posted to the model service, the {@code request_payload}
 * column of the prediction, and the input to the cache hash. Any typed intermediate
 * would have to be serialised into this shape anyway, and the conversion is precisely
 * where "the model was trained on a field the API stopped sending" bugs live.</p>
 *
 * <p>Insertion-ordered because the hash is computed over the serialised form, and a
 * {@code HashMap} would produce different hashes for identical features across JVM
 * runs — turning the cache into an expensive no-op.</p>
 */
public final class FeatureVector {

    private final Map<String, Object> values = new LinkedHashMap<>();

    public static FeatureVector create() {
        return new FeatureVector();
    }

    public FeatureVector put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    /** Adds a numeric feature, normalising nulls to zero so models never see gaps. */
    public FeatureVector putNumber(String key, Number value) {
        values.put(key, value == null ? BigDecimal.ZERO : value);
        return this;
    }

    public FeatureVector putAll(Map<String, Object> other) {
        values.putAll(other);
        return this;
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    // ------------------------------------------------------------------ typed reads

    public BigDecimal decimal(String key) {
        return decimal(key, BigDecimal.ZERO);
    }

    public BigDecimal decimal(String key, BigDecimal fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public double number(String key) {
        return decimal(key).doubleValue();
    }

    public int integer(String key) {
        return decimal(key).intValue();
    }

    public boolean flag(String key) {
        Object value = values.get(key);
        return value instanceof Boolean bool && bool;
    }

    public String text(String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String key) {
        Object value = values.get(key);
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
