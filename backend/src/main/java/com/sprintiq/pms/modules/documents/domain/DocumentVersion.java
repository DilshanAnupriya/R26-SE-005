package com.sprintiq.pms.modules.documents.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One saved edit.
 *
 * <p>Append-only: a history that can be edited is not a history. The title is kept
 * alongside the content because a rename is an edit too, and a log that tracks only
 * bodies cannot explain why a page nobody can find used to be called something else.</p>
 */
@Entity
@Table(name = "document_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentVersion extends AppendOnlyTenantEntity {

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String content;

    /** What changed, in the author's words. Optional — a forced message says "update". */
    @Column(length = 500)
    private String summary;

    @Column(name = "authored_by")
    private UUID authoredBy;
}
