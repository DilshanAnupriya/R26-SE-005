package com.sprintiq.pms.modules.documents.domain;

/**
 * Where a page is in its life.
 *
 * <p>ARCHIVED rather than deletion for pages that are no longer true: the decision
 * they record still happened, and a team that cannot find last year's reasoning
 * relitigates it.</p>
 */
public enum DocumentStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
