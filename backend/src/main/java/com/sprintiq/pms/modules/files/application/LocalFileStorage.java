package com.sprintiq.pms.modules.files.application;

import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.security.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;

/**
 * Filesystem-backed storage for development and single-node deployments.
 *
 * <p>Every path is resolved against the configured root and then checked to still be
 * inside it. The keys this application generates are safe by construction, but a storage
 * implementation that trusts its caller is one refactor away from serving
 * {@code ../../etc/passwd}, and the check costs a string comparison.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(AppProperties properties) {
        this.root = Path.of(properties.getStorage().getLocalPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create attachment directory " + root, ex);
        }
    }

    @Override
    public void store(String key, InputStream content, long sizeBytes, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            log.error("Failed to store attachment {}", key, ex);
            throw new ApplicationException(ErrorCode.INTERNAL_ERROR, "Could not store the upload");
        }
    }

    @Override
    public InputStream read(String key) {
        Path source = resolve(key);
        if (!Files.exists(source)) {
            throw new ResourceNotFoundException("Attachment content", key);
        }
        try {
            return Files.newInputStream(source, StandardOpenOption.READ);
        } catch (IOException ex) {
            throw new ApplicationException(ErrorCode.INTERNAL_ERROR, "Could not read the attachment");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            // The database row is already soft-deleted; a leftover blob is a cleanup job's
            // problem, not a reason to fail the user's delete.
            log.warn("Could not remove attachment blob {}: {}", key, ex.toString());
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    private Path resolve(String key) {
        Path candidate = root.resolve(key).normalize();
        if (!candidate.startsWith(root)) {
            throw new ApplicationException(ErrorCode.VALIDATION_ERROR, "Invalid storage key");
        }
        return candidate;
    }
}
