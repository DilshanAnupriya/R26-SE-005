package com.sprintiq.pms.modules.documents.domain;

import com.sprintiq.pms.common.domain.SoftDeletable;
import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A page or a folder in the document tree.
 *
 * <p>One entity for both, because the tree is the same shape either way and two
 * tables would mean every query over it becomes a union. {@code kind} is what stops
 * them blurring: a folder carries no body, and the database enforces that rather than
 * trusting every caller to remember.</p>
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document extends TenantAwareEntity implements SoftDeletable {

    /** Null for an organization-wide page — a handbook belongs to no project. */
    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private DocumentKind kind = DocumentKind.DOCUMENT;

    @Column(nullable = false)
    private String title;

    /** Markdown. Text rather than parsed structure, so it survives a new renderer. */
    @Column(columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private DocumentStatus status = DocumentStatus.DRAFT;

    @Column(nullable = false)
    @Builder.Default
    private short position = 0;

    /** The head version number. Incremented only when the content or title changes. */
    @Column(name = "version_no", nullable = false)
    @Builder.Default
    private int versionNo = 1;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isFolder() {
        return kind == DocumentKind.FOLDER;
    }

    /**
     * Whether an edit is substantive enough to keep.
     *
     * <p>Moving a page, publishing it or reordering it is not a new version — a
     * history full of "moved to Engineering" entries buries the three edits that
     * actually changed what the page says.</p>
     */
    public boolean isSubstantiveChange(String newTitle, String newContent) {
        boolean titleChanged = newTitle != null && !newTitle.equals(title);
        boolean contentChanged = newContent != null && !newContent.equals(content);
        return titleChanged || contentChanged;
    }
}
