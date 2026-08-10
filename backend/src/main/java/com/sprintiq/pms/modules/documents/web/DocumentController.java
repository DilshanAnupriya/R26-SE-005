package com.sprintiq.pms.modules.documents.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.modules.documents.application.DocumentService;
import com.sprintiq.pms.modules.documents.web.dto.DocumentDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Documents", description = "The document tree and its history")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "The whole tree")
    @PreAuthorize("@perm.has('document:read')")
    @GetMapping
    public TreeResponse tree(@RequestParam(required = false) UUID projectId) {
        return documentService.tree(projectId);
    }

    @Operation(summary = "Search page titles")
    @PreAuthorize("@perm.has('document:read')")
    @GetMapping("/search")
    public List<DocumentResponse> search(@RequestParam String q) {
        return documentService.search(q);
    }

    @Operation(summary = "One page with its content")
    @PreAuthorize("@perm.has('document:read')")
    @GetMapping("/{documentId}")
    public DocumentResponse get(@PathVariable UUID documentId) {
        return documentService.get(documentId);
    }

    @Operation(summary = "Every saved edit, newest first")
    @PreAuthorize("@perm.has('document:read')")
    @GetMapping("/{documentId}/versions")
    public List<VersionResponse> history(@PathVariable UUID documentId) {
        return documentService.history(documentId);
    }

    @Operation(summary = "One version, with the words it held")
    @PreAuthorize("@perm.has('document:read')")
    @GetMapping("/{documentId}/versions/{versionNo}")
    public VersionResponse version(@PathVariable UUID documentId, @PathVariable int versionNo) {
        return documentService.version(documentId, versionNo);
    }

    @Operation(summary = "Create a page or a folder")
    @PreAuthorize("@perm.has('document:write')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public DocumentResponse create(@Valid @RequestBody CreateDocumentRequest request) {
        return documentService.create(request);
    }

    @Operation(summary = "Edit a page; a title or body change keeps a version")
    @PreAuthorize("@perm.has('document:write')")
    @PatchMapping("/{documentId}")
    public DocumentResponse update(@PathVariable UUID documentId,
                                   @Valid @RequestBody UpdateDocumentRequest request) {
        return documentService.update(documentId, request);
    }

    @Operation(summary = "Put an old version's words back, as a new version")
    @PreAuthorize("@perm.has('document:write')")
    @PostMapping("/{documentId}/versions/{versionNo}/restore")
    public DocumentResponse restore(@PathVariable UUID documentId, @PathVariable int versionNo) {
        return documentService.restore(documentId, versionNo);
    }

    @Operation(summary = "Delete a page or folder, and everything under it")
    @PreAuthorize("@perm.has('document:delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{documentId}")
    public void delete(@PathVariable UUID documentId) {
        documentService.delete(documentId);
    }
}
