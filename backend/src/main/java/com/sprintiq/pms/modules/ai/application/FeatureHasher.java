package com.sprintiq.pms.modules.ai.application;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * SHA-256 over the model code, version and the serialised feature vector.
 *
 * <p>Hashing the features rather than the entity id is what makes the prediction cache
 * correct instead of merely fast. Keyed by story id, an estimate would go stale the
 * moment someone edited the story and would keep being served until a TTL expired —
 * showing a number computed from a description that no longer exists. Keyed by the
 * features, an edit produces a different hash and therefore a fresh prediction,
 * automatically, with no invalidation logic to forget.</p>
 *
 * <p>The version is part of the digest so that deploying a new model version does not
 * inherit the old one's cached answers.</p>
 */
@Slf4j
public final class FeatureHasher {

    private FeatureHasher() {
    }

    public static String hash(String modelCode, String modelVersion, Map<String, Object> features) {
        StringBuilder canonical = new StringBuilder(modelCode).append('|').append(modelVersion).append('|');
        // The vector is insertion-ordered by construction, so iteration order is stable
        // across JVMs and this string is reproducible.
        features.forEach((key, value) -> canonical.append(key).append('=')
                .append(value == null ? "" : value).append(';'));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandatory on every JVM; if it is missing, caching is the least
            // of our problems — degrade to no caching rather than failing the request.
            log.warn("SHA-256 unavailable; prediction caching disabled");
            return null;
        }
    }
}
