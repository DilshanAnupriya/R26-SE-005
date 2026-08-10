-- =====================================================================
-- V10 · Roadmap initiatives
--
-- An initiative is a body of work large enough to be worth naming on a
-- roadmap and too large to be one epic. It is deliberately NOT another goal:
-- a goal is an outcome you are trying to cause, an initiative is the work you
-- intend to do. Teams that conflate the two end up with a roadmap of wishes.
--
-- Progress is derived from the epics that ladder into it, never typed in. A
-- roadmap whose percentages are hand-maintained is a slide, not a plan — it
-- reports whatever the last person to remember it believed.
-- =====================================================================

CREATE TABLE initiatives (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    -- Nullable, because the interesting initiatives are usually the ones that
    -- cross projects. The roadmap lanes group by project and gather these into
    -- a "cross-project" lane rather than hiding them.
    project_id        UUID REFERENCES projects(id) ON DELETE CASCADE,

    title             VARCHAR(255) NOT NULL,
    description       TEXT,

    -- Dates rather than a stored quarter label. The lane a card sits in is a
    -- function of its target date, so storing both invites them to disagree,
    -- and an initiative that slips a quarter should move on the board by
    -- itself rather than waiting for someone to retype it.
    start_date        DATE,
    target_date       DATE,

    status            VARCHAR(24) NOT NULL DEFAULT 'PLANNED',
    confidence        VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    owner_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,

    -- Cached roll-up of the linked epics, 0-100, recomputed whenever the link
    -- set changes or the sweep runs. Stored so drawing a roadmap of thirty
    -- initiatives does not aggregate the whole backlog on every read.
    progress_pct      NUMERIC(5,2) NOT NULL DEFAULT 0,
    -- Ordering within a lane. Roadmap rows are arranged by hand — the order is
    -- an editorial judgement about what matters, not a sort key.
    position          SMALLINT NOT NULL DEFAULT 0,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at        TIMESTAMPTZ,

    CONSTRAINT ck_initiative_status
        CHECK (status IN ('PLANNED','IN_PROGRESS','AT_RISK','SHIPPED','CANCELLED')),
    CONSTRAINT ck_initiative_confidence CHECK (confidence IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT ck_initiative_dates
        CHECK (target_date IS NULL OR start_date IS NULL OR target_date >= start_date)
);
CREATE INDEX idx_initiatives_org ON initiatives(organization_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_initiatives_project ON initiatives(project_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_initiatives_target ON initiatives(organization_id, target_date)
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- What delivery work an initiative is made of.
--
-- A link table rather than an initiative_id column on epics: epics belong to
-- the backlog module, and a roadmap that writes to another module's table is
-- the coupling the architecture tests exist to prevent. Same shape as
-- key_result_links, for the same reason.
-- ---------------------------------------------------------------------
CREATE TABLE initiative_items (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    initiative_id     UUID NOT NULL REFERENCES initiatives(id) ON DELETE CASCADE,
    entity_type       VARCHAR(20) NOT NULL,
    entity_id         UUID NOT NULL,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_initiative_item UNIQUE (initiative_id, entity_type, entity_id),
    CONSTRAINT ck_initiative_item_type CHECK (entity_type IN ('EPIC','STORY','MILESTONE'))
);
CREATE INDEX idx_initiative_items_entity ON initiative_items(entity_type, entity_id);
CREATE INDEX idx_initiative_items_initiative ON initiative_items(initiative_id);

-- ---------------------------------------------------------------------
-- Permissions. Same split as goals: everyone can read the roadmap, because a
-- roadmap nobody can see is not doing its job; editing it is a planning act.
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, resource, action, description) VALUES
 ('initiative:read','initiative','read','View the roadmap and its initiatives'),
 ('initiative:manage','initiative','manage','Create and edit initiatives, and link work to them');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('OWNER','ADMIN','MANAGER')
  AND p.code IN ('initiative:read','initiative:manage');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('MEMBER','VIEWER') AND p.code = 'initiative:read';
