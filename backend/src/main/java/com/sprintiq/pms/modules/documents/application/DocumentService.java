package com.sprintiq.pms.modules.documents.application;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.audit.application.AuditService;
import com.sprintiq.pms.modules.documents.domain.*;
import com.sprintiq.pms.modules.documents.web.dto.DocumentDtos.*;
import com.sprintiq.pms.modules.users.application.UserService;
import com.sprintiq.pms.modules.users.application.UserSummary;
import com.sprintiq.pms.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The document tree and its history.
 *
 * <p>Every substantive edit is kept. A wiki without history is a place where
 * somebody's careful page becomes somebody else's careless paste with no way back,
 * which is precisely why teams abandon internal docs and go back to sending files.</p>
 *
 * <p>What counts as substantive is deliberately narrow: the title or the body. Moving
 * a page between folders, publishing it, or reordering it is not a version — a history
 * full of "moved to Engineering" buries the three edits that changed what it says.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    /** Bounds folder nesting. Deep enough for any real structure, shallow enough to render. */
    private static final int MAX_DEPTH = 8;

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final UserService userService;
    private final AuditService auditService;

    // ------------------------------------------------------------------ queries

    /**
     * The whole tree, assembled in memory from one query.
     *
     * <p>A tree walked one level per request is N round trips for N folders and the
     * reader waits for all of them before anything appears. A document tree is small
     * enough that fetching it whole is simply the right answer.</p>
     */
    @Transactional(readOnly = true)
    public TreeResponse tree(UUID projectId) {
        List<Document> all = documentRepository.findTree(projectId);

        Map<UUID, List<Document>> byParent = all.stream()
                .collect(Collectors.groupingBy(
                        document -> document.getParentId() == null ? ROOT : document.getParentId(),
                        LinkedHashMap::new, Collectors.toList()));

        List<NodeResponse> roots = byParent.getOrDefault(ROOT, List.of()).stream()
                .map(document -> toNode(document, byParent, 0))
                .toList();

        return new TreeResponse(roots,
                (int) all.stream().filter(document -> !document.isFolder()).count(),
                (int) all.stream().filter(Document::isFolder).count());
    }

    /** Sentinel for the root bucket; never persisted. */
    private static final UUID ROOT = new UUID(0L, 0L);

    private NodeResponse toNode(Document document, Map<UUID, List<Document>> byParent, int depth) {
        // Depth-bounded as well as cycle-free by construction: a corrupt parent chain
        // in the data would otherwise recurse until the stack gives out.
        List<NodeResponse> children = depth >= MAX_DEPTH
                ? List.of()
                : byParent.getOrDefault(document.getId(), List.of()).stream()
                        .map(child -> toNode(child, byParent, depth + 1))
                        .toList();

        return new NodeResponse(document.getId(), document.getParentId(), document.getKind(),
                document.getTitle(), document.getStatus(), document.getPosition(),
                document.getUpdatedAt(), children);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID documentId) {
        Document document = require(documentId);
        return toResponse(document, userService.summariesFor(
                document.getUpdatedBy() == null ? List.of() : List.of(document.getUpdatedBy())));
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> search(String term) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        List<Document> found = documentRepository.search("%" + term.toLowerCase(Locale.ROOT) + "%");
        Map<UUID, UserSummary> people = userService.summariesFor(
                found.stream().map(Document::getUpdatedBy).filter(Objects::nonNull).toList());
        return found.stream().map(document -> toResponse(document, people)).toList();
    }

    @Transactional(readOnly = true)
    public List<VersionResponse> history(UUID documentId) {
        require(documentId);
        List<DocumentVersion> versions = versionRepository
                .findByDocumentIdOrderByVersionNoDesc(documentId);
        Map<UUID, UserSummary> people = userService.summariesFor(
                versions.stream().map(DocumentVersion::getAuthoredBy).filter(Objects::nonNull).toList());

        // Content omitted from the list: a history of twenty revisions of a long page
        // is megabytes of body nobody asked for.
        return versions.stream().map(version -> toResponse(version, people, false)).toList();
    }

    @Transactional(readOnly = true)
    public VersionResponse version(UUID documentId, int versionNo) {
        require(documentId);
        DocumentVersion version = versionRepository
                .findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(() -> new ResourceNotFoundException("Document version", documentId));
        return toResponse(version, userService.summariesFor(
                version.getAuthoredBy() == null ? List.of() : List.of(version.getAuthoredBy())), true);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public DocumentResponse create(CreateDocumentRequest request) {
        UUID organizationId = TenantContext.requireOrganizationId();
        DocumentKind kind = request.kind() == null ? DocumentKind.DOCUMENT : request.kind();

        if (kind == DocumentKind.FOLDER && request.content() != null) {
            throw new BusinessRuleException("A folder holds pages; it has no body of its own");
        }
        if (request.parentId() != null) {
            assertParentIsAFolder(request.parentId());
            assertDepthAllows(request.parentId());
        }

        short position = (short) (documentRepository.findMaxPosition(request.parentId())
                .orElse((short) -1) + 1);

        Document document = Document.builder()
                .projectId(request.projectId())
                .parentId(request.parentId())
                .kind(kind)
                .title(request.title().trim())
                .content(kind == DocumentKind.FOLDER ? null : request.content())
                .status(request.status() == null ? DocumentStatus.DRAFT : request.status())
                .position(position)
                .versionNo(1)
                .build();
        document.setOrganizationId(organizationId);
        document = documentRepository.save(document);

        if (kind == DocumentKind.DOCUMENT) {
            recordVersion(document, "Created", organizationId);
        }
        auditService.record("DOCUMENT_CREATED", "Document", document.getId(), null,
                Map.of("title", document.getTitle(), "kind", kind.name()));

        return get(document.getId());
    }

    @Transactional
    public DocumentResponse update(UUID documentId, UpdateDocumentRequest request) {
        Document document = require(documentId);
        UUID organizationId = document.getOrganizationId();

        if (document.isFolder() && request.content() != null) {
            throw new BusinessRuleException("A folder holds pages; it has no body of its own");
        }

        boolean substantive = document.isSubstantiveChange(
                request.title() == null ? null : request.title().trim(), request.content());

        if (request.title() != null) document.setTitle(request.title().trim());
        if (request.content() != null) document.setContent(request.content());
        if (request.status() != null) document.setStatus(request.status());
        if (request.position() != null) document.setPosition(request.position());
        if (request.parentId() != null) {
            assertParentIsAFolder(request.parentId());
            assertNotItsOwnAncestor(documentId, request.parentId());
            assertDepthAllows(request.parentId());
            document.setParentId(request.parentId());
        }

        if (substantive && !document.isFolder()) {
            document.setVersionNo(document.getVersionNo() + 1);
        }
        document = documentRepository.save(document);

        if (substantive && !document.isFolder()) {
            recordVersion(document, request.summary(), organizationId);
        }
        return get(documentId);
    }

    /**
     * Puts an old version's words back, as a new version.
     *
     * <p>Never by rewinding the counter. A restore is an edit somebody made today, and
     * a history that can move backwards is one where "what did this say in March" has
     * no answer.</p>
     */
    @Transactional
    public DocumentResponse restore(UUID documentId, int versionNo) {
        Document document = require(documentId);
        DocumentVersion version = versionRepository
                .findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(() -> new ResourceNotFoundException("Document version", documentId));

        document.setTitle(version.getTitle());
        document.setContent(version.getContent());
        document.setVersionNo(document.getVersionNo() + 1);
        document = documentRepository.save(document);

        recordVersion(document, "Restored version %d".formatted(versionNo),
                document.getOrganizationId());
        auditService.record("DOCUMENT_RESTORED", "Document", documentId, null,
                Map.of("restoredFrom", versionNo));

        return get(documentId);
    }

    @Transactional
    public void delete(UUID documentId) {
        Document document = require(documentId);

        // Children go with the parent. Leaving them behind orphans a subtree that
        // nothing links to and nobody can find — deleted in effect, but invisibly.
        List<Document> doomed = new ArrayList<>();
        collectSubtree(document, doomed, 0);

        Instant now = Instant.now();
        doomed.forEach(node -> node.setDeletedAt(now));
        documentRepository.saveAll(doomed);

        auditService.record("DOCUMENT_DELETED", "Document", documentId, null,
                Map.of("title", document.getTitle(), "descendants", doomed.size() - 1));
    }

    // ------------------------------------------------------------------ internals

    private Document require(UUID documentId) {
        return documentRepository.findByIdAndDeletedAtIsNull(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    private void collectSubtree(Document node, List<Document> into, int depth) {
        into.add(node);
        if (depth >= MAX_DEPTH) {
            return;
        }
        for (Document child : documentRepository.findByParentIdAndDeletedAtIsNull(node.getId())) {
            collectSubtree(child, into, depth + 1);
        }
    }

    private void assertParentIsAFolder(UUID parentId) {
        Document parent = require(parentId);
        if (!parent.isFolder()) {
            throw new BusinessRuleException("Pages nest inside folders, not inside other pages");
        }
    }

    /** A page cannot be moved inside its own subtree; that detaches the whole branch. */
    private void assertNotItsOwnAncestor(UUID documentId, UUID newParentId) {
        UUID cursor = newParentId;
        int hops = 0;
        while (cursor != null && hops++ <= MAX_DEPTH) {
            if (cursor.equals(documentId)) {
                throw new BusinessRuleException(
                        "That would put the folder inside itself, cutting it off from the tree");
            }
            cursor = documentRepository.findByIdAndDeletedAtIsNull(cursor)
                    .map(Document::getParentId)
                    .orElse(null);
        }
    }

    private void assertDepthAllows(UUID parentId) {
        int depth = 0;
        UUID cursor = parentId;
        while (cursor != null && depth <= MAX_DEPTH) {
            depth++;
            cursor = documentRepository.findByIdAndDeletedAtIsNull(cursor)
                    .map(Document::getParentId)
                    .orElse(null);
        }
        if (depth >= MAX_DEPTH) {
            throw new BusinessRuleException(
                    "Folders nest %d deep at most — past that nobody finds anything".formatted(MAX_DEPTH));
        }
    }

    private void recordVersion(Document document, String summary, UUID organizationId) {
        DocumentVersion version = DocumentVersion.builder()
                .documentId(document.getId())
                .versionNo(document.getVersionNo())
                .title(document.getTitle())
                .content(document.getContent())
                .summary(summary)
                .authoredBy(TenantContext.userId().orElse(null))
                .build();
        version.setOrganizationId(organizationId);
        versionRepository.save(version);
    }

    private static DocumentResponse toResponse(Document document, Map<UUID, UserSummary> people) {
        UserSummary editor = document.getUpdatedBy() == null ? null : people.get(document.getUpdatedBy());
        return new DocumentResponse(document.getId(), document.getParentId(), document.getProjectId(),
                document.getKind(), document.getTitle(), document.getContent(), document.getStatus(),
                document.getPosition(), document.getVersionNo(), document.getUpdatedBy(),
                editor == null ? null : editor.fullName(),
                document.getCreatedAt(), document.getUpdatedAt());
    }

    private static VersionResponse toResponse(DocumentVersion version,
                                              Map<UUID, UserSummary> people, boolean withContent) {
        UserSummary author = version.getAuthoredBy() == null ? null : people.get(version.getAuthoredBy());
        return new VersionResponse(version.getId(), version.getVersionNo(), version.getTitle(),
                version.getSummary(), version.getAuthoredBy(),
                author == null ? null : author.fullName(), version.getCreatedAt(),
                withContent ? version.getContent() : null);
    }
}
