-- =====================================================================
-- V9 · Goals and key results (OKRs)
--
-- An objective is a sentence; a key result is a number that moves. Modelling
-- them as separate tables rather than one "goal with a target" is what makes an
-- OKR an OKR: one objective is measured by several results, each with its own
-- unit and its own trajectory.
--
-- Key results link to the epics that advance them, which is the join that turns
-- OKRs from a parallel document into something the delivery data can answer.
-- =====================================================================

CREATE TABLE goals (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workspace_id      UUID REFERENCES workspaces(id) ON DELETE SET NULL,
    -- Nullable: an objective can belong to the whole organization rather than one
    -- project, which is the common case for a quarterly company goal.
    project_id        UUID REFERENCES projects(id) ON DELETE CASCADE,
    parent_goal_id    UUID REFERENCES goals(id) ON DELETE SET NULL,

    title             VARCHAR(255) NOT NULL,
    description       TEXT,
    -- The period the objective is scoped to, e.g. '2026-Q3'. Kept as text rather
    -- than a date range because teams name their cycles and those names appear in
    -- conversation; the dates below carry the machine-readable version.
    period            VARCHAR(32),
    start_date        DATE,
    target_date       DATE,

    status            VARCHAR(24) NOT NULL DEFAULT 'ON_TRACK',
    level             VARCHAR(24) NOT NULL DEFAULT 'TEAM',
    owner_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,

    -- Cached roll-up of the key results, 0-100. Stored so a list of goals does not
    -- need to aggregate every child on every read; recomputed whenever a key
    -- result moves.
    progress_pct      NUMERIC(5,2) NOT NULL DEFAULT 0,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at        TIMESTAMPTZ,

    CONSTRAINT ck_goal_status CHECK (status IN ('ON_TRACK','AT_RISK','OFF_TRACK','ACHIEVED','MISSED','DRAFT')),
    CONSTRAINT ck_goal_level CHECK (level IN ('ORGANIZATION','WORKSPACE','TEAM','INDIVIDUAL')),
    CONSTRAINT ck_goal_dates CHECK (target_date IS NULL OR start_date IS NULL OR target_date >= start_date),
    -- A goal cannot be its own parent. Deeper cycles are rejected in the service,
    -- which can walk the chain; a check constraint cannot.
    CONSTRAINT ck_goal_not_self_parent CHECK (parent_goal_id IS NULL OR parent_goal_id <> id)
);
CREATE INDEX idx_goals_org ON goals(organization_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_goals_project ON goals(project_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_goals_period ON goals(organization_id, period) WHERE deleted_at IS NULL;
CREATE INDEX idx_goals_parent ON goals(parent_goal_id);

CREATE TABLE key_results (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    goal_id           UUID NOT NULL REFERENCES goals(id) ON DELETE CASCADE,

    title             VARCHAR(255) NOT NULL,
    -- How the number is read. PERCENT and CURRENCY render differently from a raw
    -- count, and BOOLEAN is the "did it ship" case that would otherwise be faked
    -- with a 0-to-1 numeric.
    metric_type       VARCHAR(24) NOT NULL DEFAULT 'NUMBER',
    unit              VARCHAR(24),

    start_value       NUMERIC(14,2) NOT NULL DEFAULT 0,
    current_value     NUMERIC(14,2) NOT NULL DEFAULT 0,
    target_value      NUMERIC(14,2) NOT NULL,
    -- Some results improve by going down (defect count, cycle time). Without this
    -- the progress maths reports a genuine improvement as negative progress.
    direction         VARCHAR(16) NOT NULL DEFAULT 'INCREASE',

    status            VARCHAR(24) NOT NULL DEFAULT 'ON_TRACK',
    owner_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    position          SMALLINT NOT NULL DEFAULT 0,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at        TIMESTAMPTZ,

    CONSTRAINT ck_kr_metric CHECK (metric_type IN ('NUMBER','PERCENT','CURRENCY','BOOLEAN')),
    CONSTRAINT ck_kr_direction CHECK (direction IN ('INCREASE','DECREASE')),
    CONSTRAINT ck_kr_status CHECK (status IN ('ON_TRACK','AT_RISK','OFF_TRACK','ACHIEVED','MISSED')),
    -- A target equal to the start makes progress undefined (division by zero).
    CONSTRAINT ck_kr_target_differs CHECK (target_value <> start_value)
);
CREATE INDEX idx_key_results_goal ON key_results(goal_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_key_results_org ON key_results(organization_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- What delivery work advances a key result.
--
-- Polymorphic on (entity_type, entity_id) to match the other association tables
-- in this schema, so an epic today and a milestone tomorrow need no new table.
-- ---------------------------------------------------------------------
CREATE TABLE key_result_links (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    key_result_id     UUID NOT NULL REFERENCES key_results(id) ON DELETE CASCADE,
    entity_type       VARCHAR(20) NOT NULL,
    entity_id         UUID NOT NULL,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_key_result_link UNIQUE (key_result_id, entity_type, entity_id),
    CONSTRAINT ck_krl_entity_type CHECK (entity_type IN ('EPIC','STORY','MILESTONE','PROJECT'))
);
CREATE INDEX idx_key_result_links_entity ON key_result_links(entity_type, entity_id);

-- ---------------------------------------------------------------------
-- Check-ins: the history of a key result's value.
--
-- Separate from current_value because "where is this now" and "how did it get
-- here" are different questions. Without the history a goal that quietly slipped
-- and recovered looks identical to one that never moved.
-- ---------------------------------------------------------------------
CREATE TABLE key_result_checkins (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    key_result_id     UUID NOT NULL REFERENCES key_results(id) ON DELETE CASCADE,

    value             NUMERIC(14,2) NOT NULL,
    previous_value    NUMERIC(14,2),
    confidence        SMALLINT,
    note              VARCHAR(1000),
    recorded_by       UUID REFERENCES users(id) ON DELETE SET NULL,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_krc_confidence CHECK (confidence IS NULL OR confidence BETWEEN 1 AND 5)
);
CREATE INDEX idx_kr_checkins ON key_result_checkins(key_result_id, recorded_at DESC);

-- ---------------------------------------------------------------------
-- Permissions. Reading goals comes with the general read grant that VIEWER has;
-- managing them is a planning activity, so it lands with MANAGER and above.
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, resource, action, description) VALUES
 ('goal:read','goal','read','View goals and key results'),
 ('goal:manage','goal','manage','Create and edit goals, and record check-ins');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('OWNER','ADMIN','MANAGER') AND p.code IN ('goal:read','goal:manage');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('MEMBER','VIEWER') AND p.code = 'goal:read';
