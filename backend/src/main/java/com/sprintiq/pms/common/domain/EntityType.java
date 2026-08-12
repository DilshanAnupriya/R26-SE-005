package com.sprintiq.pms.common.domain;

/**
 * Discriminator for the polymorphic association tables — activity, comments,
 * attachments, watchers, labels and dependencies all point at "some item" by
 * {@code (entity_type, entity_id)} rather than carrying one nullable foreign key per
 * item kind.
 *
 * <p>It lives in {@code common} because those tables are owned by five different
 * modules and every one of them writes the same strings into the same columns. A
 * module-local copy would have to be imported across module boundaries — which the
 * layering rules forbid — or duplicated, and duplicated string vocabularies drift
 * until a query silently stops matching.</p>
 */
public enum EntityType {

    PROJECT,
    MILESTONE,
    REQUIREMENT,
    EPIC,
    STORY,
    TASK,
    SPRINT;

    /** Items that carry a {@code PROJ-142} key and appear on boards and in the backlog. */
    public boolean isWorkItem() {
        return this == EPIC || this == STORY || this == TASK;
    }

    /** Items that can be labelled. */
    public boolean isLabelable() {
        return isWorkItem();
    }
}
