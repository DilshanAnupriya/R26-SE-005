package com.sprintiq.pms.modules.files.application;

import com.sprintiq.pms.common.collaboration.ItemReferenceRegistry;
import com.sprintiq.pms.common.collaboration.ItemReferenceResolver.ItemReference;
import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.AccessDeniedAppException;
import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.files.domain.Attachment;
import com.sprintiq.pms.modules.files.domain.AttachmentRepository;
import com.sprintiq.pms.modules.files.domain.ScanStatus;
import com.sprintiq.pms.modules.files.web.dto.AttachmentDtos.AttachmentResponse;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.security.AppProperties;
import com.sprintiq.pms.security.PermissionService;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Uploads, downloads and removal of attachments.
 *
 * <p>Three things are enforced here that a naive implementation gets wrong: the content
 * type is checked against an allow-list rather than a deny-list, the stored name is
 * never used to build a path, and the declared type is verified against what the browser
 * actually sent rather than trusted from the filename extension.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRepository attachments;
    private final FileStorage storage;
    private final ItemReferenceRegistry items;
    private final UserService userService;
    private final PermissionService permissions;
    private final AppProperties properties;

    @Transactional
    public AttachmentResponse upload(EntityType entityType, UUID entityId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_ERROR, "No file was uploaded");
        }
        String contentType = normaliseContentType(file.getContentType());
        requireAllowedType(contentType);

        ItemReference item = items.resolve(entityType, entityId);
        UUID organizationId = TenantContext.requireOrganizationId();
        UUID uploader = TenantContext.requireUserId();

        // Key layout keeps one tenant's blobs together, which makes an export or a
        // deletion request a directory operation rather than a table scan.
        String storageKey = "%s/%s/%s/%s".formatted(organizationId, entityType.name().toLowerCase(
                Locale.ROOT), entityId, UUID.randomUUID());

        // Checksummed before storing: a stream can only be read once, and a corrupt
        // upload should be detected before it is written rather than after.
        String checksum = checksumOf(file);
        try (InputStream content = file.getInputStream()) {
            storage.store(storageKey, content, file.getSize(), contentType);
        } catch (IOException ex) {
            throw new ApplicationException(ErrorCode.INTERNAL_ERROR, "Could not read the upload");
        }

        Attachment attachment = Attachment.builder()
                .projectId(item.projectId())
                .entityType(entityType)
                .entityId(entityId)
                .fileName(safeDisplayName(file.getOriginalFilename()))
                .storageKey(storageKey)
                .contentType(contentType)
                .sizeBytes(file.getSize())
                .checksumSha256(checksum)
                .scanStatus(ScanStatus.PENDING)
                .uploadedBy(uploader)
                .build();

        return toResponse(attachments.save(attachment),
                userService.summariesFor(List.of(uploader)));
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(EntityType entityType, UUID entityId) {
        List<Attachment> found = attachments
                .findByEntityTypeAndEntityIdAndDeletedAtIsNullOrderByCreatedAtDesc(entityType, entityId);
        Map<UUID, UserSummary> uploaders = userService.summariesFor(
                found.stream().map(Attachment::getUploadedBy).toList());
        return found.stream().map(attachment -> toResponse(attachment, uploaders)).toList();
    }

    @Transactional(readOnly = true)
    public AttachmentResponse get(UUID attachmentId) {
        Attachment attachment = require(attachmentId);
        return toResponse(attachment, userService.summariesFor(List.of(attachment.getUploadedBy())));
    }

    /** The bytes, plus the metadata a download response needs. */
    @Transactional(readOnly = true)
    public Download download(UUID attachmentId) {
        Attachment attachment = require(attachmentId);
        if (!attachment.getScanStatus().isDownloadable()) {
            throw new ApplicationException(ErrorCode.ACCESS_DENIED,
                    "This file was flagged by the malware scan and cannot be downloaded");
        }
        return new Download(attachment.getFileName(), attachment.getContentType(),
                attachment.getSizeBytes(), storage.read(attachment.getStorageKey()));
    }

    public record Download(String fileName, String contentType, long sizeBytes, InputStream content) {}

    /**
     * Removes an attachment.
     *
     * <p>Uploaders can always remove their own; removing someone else's needs
     * {@code attachment:delete}. The row is soft-deleted and the blob removed
     * immediately — a file someone deleted because it should not have been shared has to
     * actually stop being served, and a recycle bin for bytes would defeat that.</p>
     */
    @Transactional
    public void delete(UUID attachmentId) {
        Attachment attachment = require(attachmentId);
        boolean isUploader = attachment.getUploadedBy().equals(TenantContext.requireUserId());
        if (!isUploader && !permissions.has("attachment:delete")) {
            throw AccessDeniedAppException.missingPermission("attachment:delete");
        }
        attachment.setDeletedAt(Instant.now());
        attachments.save(attachment);
        storage.delete(attachment.getStorageKey());
    }

    // ------------------------------------------------------------------ internals

    private Attachment require(UUID attachmentId) {
        return attachments.findByIdAndDeletedAtIsNull(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", attachmentId));
    }

    private void requireAllowedType(String contentType) {
        List<String> allowed = properties.getStorage().getAllowedContentTypes();
        if (allowed.isEmpty()) {
            // An empty allow-list means "nothing configured", not "everything permitted".
            throw new ApplicationException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "File uploads are not configured on this deployment");
        }
        if (!allowed.contains(contentType)) {
            throw new ApplicationException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "%s files are not accepted".formatted(contentType));
        }
    }

    private String normaliseContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        // "text/csv; charset=utf-8" and "text/csv" are the same type for this purpose.
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator))
                .trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Strips directories and control characters from the display name.
     *
     * <p>It is never used as a path — the storage key is generated — but it is echoed
     * back into a {@code Content-Disposition} header and rendered in the UI, and both of
     * those have their own injection stories.</p>
     */
    private String safeDisplayName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "attachment";
        }
        String base = originalName.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[\\p{Cntrl}\"\\r\\n]", "").trim();
        if (base.isEmpty()) {
            return "attachment";
        }
        return base.length() <= 255 ? base : base.substring(0, 255);
    }

    private String checksumOf(MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = content.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            // A missing checksum is a lost integrity check, not a failed upload.
            log.warn("Could not checksum upload {}: {}", file.getOriginalFilename(), ex.toString());
            return null;
        }
    }

    private AttachmentResponse toResponse(Attachment attachment, Map<UUID, UserSummary> uploaders) {
        UserSummary uploader = uploaders.get(attachment.getUploadedBy());
        return new AttachmentResponse(attachment.getId(), attachment.getEntityType(),
                attachment.getEntityId(), attachment.getFileName(), attachment.getContentType(),
                attachment.getSizeBytes(), attachment.isImage(), attachment.getScanStatus(),
                attachment.getUploadedBy(), uploader == null ? null : uploader.fullName(),
                "/api/v1/attachments/%s/download".formatted(attachment.getId()),
                attachment.getCreatedAt());
    }
}
