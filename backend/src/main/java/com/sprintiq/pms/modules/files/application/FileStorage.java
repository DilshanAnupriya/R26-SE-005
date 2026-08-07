package com.sprintiq.pms.modules.files.application;

import java.io.InputStream;

/**
 * Where uploaded bytes live.
 *
 * <p>An interface with one local implementation today, because the alternative is
 * {@code Files.write} calls sprinkled through a service that will later need S3, and
 * moving to object storage should be a new class rather than an archaeology exercise.
 * The database never stores file contents — a 25 MB screenshot in a row makes every
 * backup, replica and query planner pay for it forever.</p>
 */
public interface FileStorage {

    /**
     * @param key opaque, server-generated. Implementations must reject anything that
     *            could escape their root.
     */
    void store(String key, InputStream content, long sizeBytes, String contentType);

    InputStream read(String key);

    void delete(String key);

    boolean exists(String key);
}
