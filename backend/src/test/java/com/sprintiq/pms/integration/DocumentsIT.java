package com.sprintiq.pms.integration;

import com.sprintiq.pms.common.error.BusinessRuleException;
import com.sprintiq.pms.modules.documents.application.DocumentService;
import com.sprintiq.pms.modules.documents.domain.DocumentKind;
import com.sprintiq.pms.modules.documents.domain.DocumentStatus;
import com.sprintiq.pms.modules.documents.web.dto.DocumentDtos.*;
import com.sprintiq.pms.modules.organizations.application.OrganizationProvisioningService;
import com.sprintiq.pms.modules.organizations.domain.Organization;
import com.sprintiq.pms.modules.users.domain.User;
import com.sprintiq.pms.modules.users.domain.UserRepository;
import com.sprintiq.pms.modules.users.domain.UserStatus;
import com.sprintiq.pms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The document tree and its history.
 *
 * <p>The rules worth pinning down are the ones that decide whether the history is
 * trustworthy: what counts as a version, what a restore does to the sequence, and
 * whether a subtree can be detached from the tree by moving it into itself.</p>
 */
@Transactional
class DocumentsIT extends AbstractIntegrationTest {

    @Autowired OrganizationProvisioningService provisioningService;
    @Autowired UserRepository userRepository;
    @Autowired DocumentService documentService;

    @BeforeEach
    void provision() {
        UUID userId = newUser();
        Organization organization = provisioningService.provisionOrganization("Acme Robotics", userId);
        TenantContext.set(TenantContext.Context.builder()
                .organizationId(organization.getId())
                .userId(userId)
                .permissions(Set.of())
                .systemContext(false)
                .build());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newUser() {
        String email = "owner+%s@example.test".formatted(UUID.randomUUID());
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .emailNormalized(email.toLowerCase(Locale.ROOT))
                .fullName("Test owner")
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(java.time.Instant.now())
                .build()).getId();
    }

    private DocumentResponse folder(String title, UUID parentId) {
        return documentService.create(new CreateDocumentRequest(title, DocumentKind.FOLDER,
                parentId, null, null, null));
    }

    private DocumentResponse page(String title, UUID parentId, String content) {
        return documentService.create(new CreateDocumentRequest(title, DocumentKind.DOCUMENT,
                parentId, null, content, null));
    }

    // ------------------------------------------------------------------ the tree

    @Test
    @DisplayName("nests folders and pages, and counts each kind")
    void nests() {
        DocumentResponse engineering = folder("Engineering", null);
        page("Runbook", engineering.id(), "# Runbook");
        page("On-call", engineering.id(), "# On-call");
        page("Handbook", null, "# Handbook");

        TreeResponse tree = documentService.tree(null);

        assertThat(tree.totalFolders()).isEqualTo(1);
        assertThat(tree.totalDocuments()).isEqualTo(3);
        assertThat(tree.roots()).hasSize(2);
        assertThat(tree.roots()).filteredOn(node -> node.title().equals("Engineering"))
                .singleElement()
                .satisfies(node -> assertThat(node.children()).hasSize(2));
    }

    @Test
    @DisplayName("refuses to nest a page inside another page")
    void pagesAreNotFolders() {
        DocumentResponse handbook = page("Handbook", null, "# Handbook");

        assertThatThrownBy(() -> page("Chapter one", handbook.id(), "..."))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("folders");
    }

    @Test
    @DisplayName("refuses a folder a body of its own")
    void foldersHaveNoBody() {
        assertThatThrownBy(() -> documentService.create(new CreateDocumentRequest(
                "Engineering", DocumentKind.FOLDER, null, null, "words", null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("refuses to move a folder inside itself, which would cut off the branch")
    void noCycles() {
        DocumentResponse outer = folder("Engineering", null);
        DocumentResponse inner = folder("Runbooks", outer.id());

        assertThatThrownBy(() -> documentService.update(outer.id(),
                new UpdateDocumentRequest(null, null, null, inner.id(), null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inside itself");
    }

    // ------------------------------------------------------------------ history

    @Test
    @DisplayName("keeps a version for a body change but not for a move or a publish")
    void versionsOnlyForSubstantiveEdits() {
        DocumentResponse folder = folder("Engineering", null);
        DocumentResponse doc = page("Runbook", null, "# Runbook");
        assertThat(documentService.history(doc.id())).hasSize(1);

        documentService.update(doc.id(),
                new UpdateDocumentRequest(null, "# Runbook\n\nStep one", null, null, null, "Added step one"));
        assertThat(documentService.history(doc.id())).hasSize(2);

        // A move and a publish change nothing about what the page says. A history full
        // of "moved to Engineering" buries the edits that did.
        documentService.update(doc.id(),
                new UpdateDocumentRequest(null, null, DocumentStatus.PUBLISHED, folder.id(), null, null));
        assertThat(documentService.history(doc.id())).hasSize(2);
    }

    @Test
    @DisplayName("a rename is an edit, because a page nobody can find has a history too")
    void renameIsAVersion() {
        DocumentResponse doc = page("Runbook", null, "# Runbook");

        documentService.update(doc.id(),
                new UpdateDocumentRequest("Incident runbook", null, null, null, null, null));

        assertThat(documentService.history(doc.id())).hasSize(2);
        assertThat(documentService.history(doc.id()).get(0).title()).isEqualTo("Incident runbook");
    }

    @Test
    @DisplayName("a restore moves the history forward, never backwards")
    void restoreAppends() {
        DocumentResponse doc = page("Runbook", null, "first");
        documentService.update(doc.id(),
                new UpdateDocumentRequest(null, "second", null, null, null, null));

        DocumentResponse restored = documentService.restore(doc.id(), 1);

        // The words are back, but as version 3. A history that can move backwards has
        // no answer to "what did this say in March".
        assertThat(restored.content()).isEqualTo("first");
        assertThat(restored.versionNo()).isEqualTo(3);
        assertThat(documentService.history(doc.id())).hasSize(3);
        assertThat(documentService.history(doc.id()).get(0).summary()).contains("version 1");
    }

    @Test
    @DisplayName("the history list omits bodies, which the single-version read supplies")
    void historyOmitsBodies() {
        DocumentResponse doc = page("Runbook", null, "a long body");

        assertThat(documentService.history(doc.id()).get(0).content()).isNull();
        assertThat(documentService.version(doc.id(), 1).content()).isEqualTo("a long body");
    }

    // ------------------------------------------------------------------ deletion

    @Test
    @DisplayName("deleting a folder takes its contents, rather than orphaning them")
    void deleteCascades() {
        DocumentResponse engineering = folder("Engineering", null);
        page("Runbook", engineering.id(), "# Runbook");
        page("On-call", engineering.id(), "# On-call");

        documentService.delete(engineering.id());

        // Left behind, those pages would be deleted in effect but invisibly — nothing
        // links to them and no tree walk reaches them.
        TreeResponse tree = documentService.tree(null);
        assertThat(tree.roots()).isEmpty();
        assertThat(tree.totalDocuments()).isZero();
    }

    @Test
    @DisplayName("finds pages by title, ignoring folders")
    void search() {
        folder("Runbooks", null);
        page("Incident runbook", null, "...");

        List<DocumentResponse> found = documentService.search("runbook");

        assertThat(found).singleElement()
                .satisfies(doc -> assertThat(doc.title()).isEqualTo("Incident runbook"));
    }
}
