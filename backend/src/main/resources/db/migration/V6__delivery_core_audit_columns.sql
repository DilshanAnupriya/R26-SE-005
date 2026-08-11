-- =====================================================================
-- V6 · Align the delivery-core join tables with BaseEntity
--
-- item_labels, item_dependencies and watchers shipped in V2 with only a
-- created_at column. Every other tenant-scoped table carries the full
-- auditing/optimistic-locking set, and TenantAwareEntity (which they must
-- extend so the tenant filter reaches them) requires it. Backfilling the
-- columns here keeps one entity superclass for the whole schema instead of
-- carving out an exception that would have to be remembered forever.
-- =====================================================================

ALTER TABLE item_labels
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN created_by UUID,
    ADD COLUMN updated_by UUID,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

ALTER TABLE item_dependencies
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_by UUID,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

ALTER TABLE watchers
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN created_by UUID,
    ADD COLUMN updated_by UUID,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

-- sprint_metrics is upserted once per sprint per day, so it needs the same set.
ALTER TABLE sprint_metrics
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN created_by UUID,
    ADD COLUMN updated_by UUID,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------
-- Backlog ranking reads (project, rank) for both sprint and backlog views;
-- V2 only indexed the un-sprinted slice.
-- ---------------------------------------------------------------------
CREATE INDEX idx_stories_project_rank ON stories(project_id, rank) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_project_rank   ON tasks(project_id, rank)   WHERE deleted_at IS NULL;

-- Epics are ranked inside a project too.
CREATE INDEX idx_epics_project_rank ON epics(project_id, rank) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- Item keys (PROJ-142) are allocated by atomically bumping item_sequence.
-- A partial unique index per project keeps a lost update from minting a
-- duplicate key even if two requests race outside the row lock.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX uq_story_key_project ON stories(project_id, item_key) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_task_key_project  ON tasks(project_id, item_key)   WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_epic_key_project  ON epics(project_id, item_key)   WHERE deleted_at IS NULL;
