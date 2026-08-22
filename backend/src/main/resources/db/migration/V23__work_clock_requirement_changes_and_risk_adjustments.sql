-- =====================================================================
-- V23 · The task work clock, the requirement-change register and the
--       sprint risk adjustments that both of them feed.
--
-- Three tables' worth of change, kept in one migration because they are
-- one feature: the board tells you what a sprint is doing, and every
-- signal it records -- a task that ran past its estimate, a task pulled
-- back out of Done, a requirement that moved after the sprint started --
-- has to reach the risk score on the same screen. Splitting them would
-- land a board that records evidence nothing reads.
-- =====================================================================


-- -- 1 · Task work clock ----------------------------------------------
--
-- A stopwatch, not a deadline. estimate_minutes already says how long a
-- task was meant to take, and spent_minutes says how much effort was
-- logged against it by hand -- but neither answers "how long has this
-- card actually been in flight", which is the question a countdown on a
-- board card is asking.
--
-- Deriving it from created_at would be wrong in the one case that
-- matters: a task parked in Done for a fortnight would go on burning its
-- estimate, and a task pulled back out of Done would either restart from
-- zero or jump forward two weeks. So the clock is stored as banked
-- seconds plus the start of the current run, and it only runs while the
-- task is outside a terminal column.
ALTER TABLE tasks
    ADD COLUMN elapsed_seconds BIGINT NOT NULL DEFAULT 0,
    -- NULL means paused. That is exactly the set of tasks sitting in DONE
    -- or CANCELLED, and the invariant is maintained by Task.applyWorkClock.
    ADD COLUMN running_since   TIMESTAMPTZ;

COMMENT ON COLUMN tasks.elapsed_seconds IS
    'Working seconds banked from runs that have already ended. Frozen while the task is terminal.';
COMMENT ON COLUMN tasks.running_since IS
    'Start of the current run, or NULL when the clock is paused (task is DONE or CANCELLED).';

-- Backfill. Logged effort is the only honest reading of past work we
-- have, so it becomes the banked total; anything still open starts a
-- fresh run from now rather than being retroactively charged for the
-- time it sat in the backlog before this column existed.
UPDATE tasks SET elapsed_seconds = GREATEST(spent_minutes, 0) * 60;
UPDATE tasks SET running_since = now()
    WHERE status NOT IN ('DONE', 'CANCELLED') AND deleted_at IS NULL;


-- -- 2 · Requirement changes -------------------------------------------
--
-- requirements.change_count already counts post-approval churn, but a
-- counter cannot say what changed, who asked for it, which sprint was in
-- flight at the time, or whether the change made the sprint harder or
-- easier. All four are what a requirement-change conversation is about,
-- and the last one is why this is not simply an activity-log query:
-- removing scope and adding it are both "a change" and they move the
-- risk in opposite directions.
CREATE TABLE requirement_changes (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id        UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,

    -- Which sprint was in flight when the change landed. Nullable: scope can
    -- move while nothing is running, and that is a materially calmer event.
    sprint_id         UUID REFERENCES sprints(id) ON DELETE SET NULL,
    -- The work the change lands on. Either, both or neither may be set -- a
    -- change can name the requirement it revises, the story that implements
    -- it, or just describe itself.
    requirement_id    UUID REFERENCES requirements(id) ON DELETE SET NULL,
    story_id          UUID REFERENCES stories(id) ON DELETE SET NULL,

    change_type       VARCHAR(40) NOT NULL,
    -- Whether this made delivery harder or easier. Fixed by the type for the
    -- two scope changes; a genuine choice for the other four, where tightening
    -- acceptance criteria and simplifying them are the same change_type.
    impact_direction  VARCHAR(10) NOT NULL DEFAULT 'INCREASE',
    description       TEXT,
    requested_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Free text for a stakeholder who has no account here, which is most of
    -- the people who ask for requirement changes.
    requested_by_name VARCHAR(120),

    -- The risk points this change contributed, resolved at write time and
    -- stored. Recomputing it later from the type would rewrite history every
    -- time the weights are tuned, and a retrospective that changes its own
    -- numbers is worse than one with stale ones.
    risk_delta        NUMERIC(6,2) NOT NULL DEFAULT 0,

    changed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_requirement_change_type CHECK (change_type IN (
        'SCOPE_ADDED', 'SCOPE_REMOVED', 'ACCEPTANCE_CRITERIA_CHANGED',
        'PRIORITY_CHANGED', 'DESCRIPTION_UPDATED', 'OTHER')),
    CONSTRAINT ck_requirement_change_direction CHECK (impact_direction IN ('INCREASE', 'DECREASE'))
);
CREATE INDEX idx_req_changes_project ON requirement_changes(project_id, changed_at DESC);
CREATE INDEX idx_req_changes_sprint ON requirement_changes(sprint_id, changed_at DESC);
CREATE INDEX idx_req_changes_requirement ON requirement_changes(requirement_id);
CREATE INDEX idx_req_changes_org ON requirement_changes(organization_id);


-- -- 3 · Sprint risk adjustments ----------------------------------------
--
-- A model scores a sprint at a moment. Between scorings the board keeps
-- moving -- a task is blocked, a task comes back from Done for the third
-- time, scope is cut -- and a dashboard that only changes when someone
-- presses Assess teaches people that the number is decorative.
--
-- So evidence recorded after a scoring accumulates here as signed points
-- against the model it bears on, and the read path adds them to the
-- stored score. This is an adjustment layer, deliberately not a
-- re-prediction: the model's own output stays on the row it was written
-- to, auditable and unedited, and every adjusted figure can be
-- decomposed back into "the model said X, the board has since added Y".
--
-- source_key is the idempotency key for the adjustments that describe a
-- state rather than an event. A task is overdue once, however many
-- browser tabs notice; a task reopened six times contributes one row
-- whose value is revised, not six rows that sum.
CREATE TABLE sprint_risk_adjustments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    sprint_id         UUID NOT NULL REFERENCES sprints(id) ON DELETE CASCADE,

    risk_type         VARCHAR(40) NOT NULL,
    delta             NUMERIC(6,2) NOT NULL,
    reason            VARCHAR(200) NOT NULL,
    source_key        VARCHAR(120),

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_adjustment_risk_type CHECK (risk_type IN (
        'OVER_BUDGET', 'REQUIREMENT_CHANGE', 'COMMUNICATION_COLLABORATION'))
);
CREATE INDEX idx_risk_adjustments_sprint ON sprint_risk_adjustments(sprint_id, created_at DESC);
CREATE INDEX idx_risk_adjustments_org ON sprint_risk_adjustments(organization_id);
-- One row per revisable signal per sprint. The partial index is what makes
-- "revise in place" enforceable rather than a convention the next caller
-- forgets.
CREATE UNIQUE INDEX uq_risk_adjustments_source
    ON sprint_risk_adjustments(sprint_id, source_key) WHERE source_key IS NOT NULL;
