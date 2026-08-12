package com.sprintiq.pms.modules.files.web;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.files.application.AttachmentService;
import com.sprintiq.pms.modules.files.web.dto.AttachmentDtos.AttachmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Tag(name = "Attachments", description = "Files attached to work items")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @Operation(summary = "Attachments on an item")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping
    public List<AttachmentResponse> list(@RequestParam EntityType entityType,
                                         @RequestParam UUID entityId) {
        return attachmentService.list(entityType, entityId);
    }

    @Operation(summary = "Upload a file",
            description = "Multipart. The content type must be on the configured allow-list; the "
                    + "stored filename is never used to build a path.")
    @PreAuthorize("@perm.has('attachment:upload')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentResponse upload(@RequestParam EntityType entityType,
                                     @RequestParam UUID entityId,
                                     @RequestPart("file") MultipartFile file) {
        return attachmentService.upload(entityType, entityId, file);
    }

    @Operation(summary = "Attachment metadata")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/{attachmentId}")
    public AttachmentResponse get(@PathVariable UUID attachmentId) {
        return attachmentService.get(attachmentId);
    }

    /**
     * Streams the bytes.
     *
     * <p>Always {@code attachment}, never {@code inline}, and always with
     * {@code X-Content-Type-Options: nosniff}. An uploaded HTML or SVG file rendered
     * inline runs on this application's origin, which turns any upload into stored XSS
     * against every colleague who clicks it.</p>
     */
    @Operation(summary = "Download the file")
    @PreAuthorize("@perm.has('project:read')")
    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID attachmentId) {
        AttachmentService.Download download = attachmentService.download(attachmentId);

        String encodedName = URLEncoder.encode(download.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .header(HttpHeaders.CONTENT_TYPE, download.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(download.sizeBytes()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, no-store")
                .body(new InputStreamResource(download.content()));
    }

    @Operation(summary = "Delete an attachment",
            description = "Your own upload, or anyone's with attachment:delete. The bytes are "
                    + "removed immediately rather than held in a recycle bin.")
    @PreAuthorize("@perm.hasAny('attachment:upload','attachment:delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{attachmentId}")
    public void delete(@PathVariable UUID attachmentId) {
        attachmentService.delete(attachmentId);
    }
}
