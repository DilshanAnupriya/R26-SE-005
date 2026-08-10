package com.sprintiq.pms.modules.documents.web.dto;

import com.sprintiq.pms.modules.documents.domain.DocumentKind;
import com.sprintiq.pms.modules.documents.domain.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DocumentDtos {

    private DocumentDtos() {}

    @Schema(name = "CreateDocumentRequest")
    public record CreateDocumentRequest(
            @NotBlank @Size(max = 255) String title,
            DocumentKind kind,
            UUID parentId,
            UUID projectId,
            String content,
            DocumentStatus status) {}

    @Schema(name = "UpdateDocumentRequest")
    public record UpdateDocumentRequest(
            @Size(max = 255) String title,
            String content,
            DocumentStatus status,
            UUID parentId,
            Short position,
            @Schema(description = "What changed, in your words. Kept with the version.")
            @Size(max = 500) String summary) {}

    /** A node without its children — the list rows and the editor header. */
    @Schema(name = "DocumentResponse")
    public record DocumentResponse(
            UUID id,
            UUID parentId,
            UUID projectId,
            DocumentKind kind,
            String title,
            String content,
            DocumentStatus status,
            short position,
            int versionNo,
            UUID updatedBy,
            String updatedByName,
            Instant createdAt,
            Instant updatedAt) {}

    /** A node with its children, for the tree. */
    @Schema(name = "DocumentNode")
    public record NodeResponse(
            UUID id,
            UUID parentId,
            DocumentKind kind,
            String title,
            DocumentStatus status,
            short position,
            Instant updatedAt,
            List<NodeResponse> children) {}

    @Schema(name = "DocumentTreeResponse")
    public record TreeResponse(
            List<NodeResponse> roots,
            int totalDocuments,
            int totalFolders) {}

    @Schema(name = "DocumentVersionResponse")
    public record VersionResponse(
            UUID id,
            int versionNo,
            String title,
            String summary,
            UUID authoredBy,
            String authoredByName,
            Instant createdAt,
            @Schema(description = "Only populated when a single version is fetched")
            String content) {}
}
