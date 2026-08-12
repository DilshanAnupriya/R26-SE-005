-- =====================================================================
-- V2 · Teams, projects, agile hierarchy, sprints, time tracking
-- =====================================================================

-- ---------- Teams & developer capability ------------------------------
CREATE TABLE teams (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workspace_id    UUID REFERENCES workspaces(id) ON DELETE SET NULL,
    name            VARCHAR(120) NOT NULL,
    description     TEXT,
    lead_user_id    UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX idx_teams_org ON teams(organization_id);

CREATE TABLE team_members (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    team_id         UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_in_team    VARCHAR(40) NOT NULL DEFAULT 'DEVELOPER',
    allocation_pct  SMALLINT NOT NULL DEFAULT 100,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_team_member UNIQUE (team_id, user_id)
);

CREATE TABLE skills (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(80) NOT NULL,
    category        VARCHAR(60),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_skill_name UNIQUE (organization_id, name)
);

-- Feature source for MODEL 3 (resource allocation) and MODEL 4 (cost)
CREATE TABLE developer_profiles (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id               UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    seniority             VARCHAR(16) NOT NULL DEFAULT 'MID',
    years_experience      NUMERIC(4,1) NOT NULL DEFAULT 0,
    hourly_rate           NUMERIC(10,2),
    weekly_capacity_hours NUMERIC(5,2) NOT NULL DEFAULT 40,
    availability_rate     NUMERIC(4,3) NOT NULL DEFAULT 1.000,
    -- Derived behavioural metrics (recomputed nightly / on sprint close)
    avg_velocity          NUMERIC(8,2) NOT NULL DEFAULT 0,
    consistency_score     NUMERIC(4,3) NOT NULL DEFAULT 0.500,
    learning_rate         NUMERIC(5,4) NOT NULL DEFAULT 0,
    defect_rate           NUMERIC(5,4) NOT NULL DEFAULT 0,
    reopen_rate           NUMERIC(5,4) NOT NULL DEFAULT 0,
    blocked_frequency     NUMERIC(5,4) NOT NULL DEFAULT 0,
    completion_variance   NUMERIC(6,3) NOT NULL DEFAULT 0,
    tasks_completed       INTEGER NOT NULL DEFAULT 0,
    last_computed_at      TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_dev_profile UNIQUE (organization_id, user_id),
    CONSTRAINT ck_seniority CHECK (seniority IN ('INTERN','JUNIOR','MID','SENIOR','LEAD','PRINCIPAL')),
    CONSTRAINT ck_consistency CHECK (consistency_score BETWEEN 0 AND 1),
    CONSTRAINT ck_availability CHECK (availability_rate BETWEEN 0 AND 1)
);

CREATE TABLE developer_skills (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    skill_id        UUID NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    proficiency     SMALLINT NOT NULL DEFAULT 3,   -- 1..5
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_dev_skill UNIQUE (user_id, skill_id),
    CONSTRAINT ck_proficiency CHECK (proficiency BETWEEN 1 AND 5)
);

-- ---------- Projects ---------------------------------------------------
CREATE TABLE projects (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workspace_id      UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    project_key       VARCHAR(10) NOT NULL,
    name              VARCHAR(150) NOT NULL,
    description       TEXT,
    status            VARCHAR(24) NOT NULL DEFAULT 'PLANNING',
    health            VARCHAR(24) NOT NULL DEFAULT 'ON_TRACK',
    progress_pct      NUMERIC(5,2) NOT NULL DEFAULT 0,
    start_date        DATE,
    target_date       DATE,
    budget_amount     NUMERIC(14,2),
    currency          CHAR(3),
    lead_user_id      UUID REFERENCES users(id),
    team_id           UUID REFERENCES teams(id),
    domain            VARCHAR(80),              -- MODEL 1 feature: project domain
    tech_stack        JSONB NOT NULL DEFAULT '[]'::jsonb, -- MODEL 1 feature
    default_sprint_days SMALLINT NOT NULL DEFAULT 14,
    definition_of_ready JSONB NOT NULL DEFAULT '[]'::jsonb,
    definition_of_done  JSONB NOT NULL DEFAULT '[]'::jsonb,
    item_sequence     INTEGER NOT NULL DEFAULT 0,  -- generates PROJ-123 keys
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT uq_project_key UNIQUE (organization_id, project_key),
    CONSTRAINT ck_project_status CHECK (status IN ('PLANNING','ACTIVE','ON_HOLD','COMPLETED','ARCHIVED')),
    CONSTRAINT ck_project_health CHECK (health IN ('ON_TRACK','AT_RISK','OFF_TRACK'))
);
CREATE INDEX idx_projects_org_ws ON projects(organization_id, workspace_id);
CREATE INDEX idx_projects_status ON projects(organization_id, status) WHERE deleted_at IS NULL;

CREATE TABLE project_members (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_role    VARCHAR(32) NOT NULL DEFAULT 'DEVELOPER',
    is_stakeholder  BOOLEAN NOT NULL DEFAULT FALSE,
    allocation_pct  SMALLINT NOT NULL DEFAULT 100,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_project_member UNIQUE (project_id, user_id),
    CONSTRAINT ck_project_role CHECK (project_role IN ('PRODUCT_OWNER','SCRUM_MASTER','TECH_LEAD','DEVELOPER','QA','DESIGNER','STAKEHOLDER'))
);
CREATE INDEX idx_project_members_user ON project_members(user_id);

CREATE TABLE workflow_states (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name            VARCHAR(60) NOT NULL,
    code            VARCHAR(40) NOT NULL,
    category        VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS', -- TODO | IN_PROGRESS | DONE
    position        SMALLINT NOT NULL DEFAULT 0,
    wip_limit       SMALLINT,
    color           VARCHAR(16),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_workflow_state UNIQUE (project_id, code),
    CONSTRAINT ck_ws_category CHECK (category IN ('TODO','IN_PROGRESS','DONE','CANCELLED'))
);

CREATE TABLE milestones (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    due_date        DATE,
    status          VARCHAR(24) NOT NULL DEFAULT 'PLANNED',
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT ck_milestone_status CHECK (status IN ('PLANNED','IN_PROGRESS','COMPLETED','MISSED'))
);
CREATE INDEX idx_milestones_project ON milestones(project_id);

CREATE TABLE requirements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    reference_code  VARCHAR(32) NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    type            VARCHAR(24) NOT NULL DEFAULT 'FUNCTIONAL',
    priority        VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    status          VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    source          VARCHAR(120),
    change_count    INTEGER NOT NULL DEFAULT 0,   -- MODEL 2 feature: requirement volatility
    last_changed_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_requirement_ref UNIQUE (project_id, reference_code),
    CONSTRAINT ck_req_type CHECK (type IN ('FUNCTIONAL','NON_FUNCTIONAL','TECHNICAL','COMPLIANCE')),
    CONSTRAINT ck_req_status CHECK (status IN ('DRAFT','APPROVED','IN_PROGRESS','IMPLEMENTED','VERIFIED','REJECTED'))
);

-- ---------- Sprints ----------------------------------------------------
CREATE TABLE sprints (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id             UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    sequence_no            INTEGER NOT NULL,
    name                   VARCHAR(120) NOT NULL,
    goal                   TEXT,
    status                 VARCHAR(24) NOT NULL DEFAULT 'PLANNED',
    start_date             DATE,
    end_date               DATE,
    duration_days          SMALLINT,
    team_size              SMALLINT,
    capacity_points        NUMERIC(8,2),
    capacity_hours         NUMERIC(8,2),
    committed_points       NUMERIC(8,2) NOT NULL DEFAULT 0,
    completed_points       NUMERIC(8,2) NOT NULL DEFAULT 0,
    added_points           NUMERIC(8,2) NOT NULL DEFAULT 0,
    removed_points         NUMERIC(8,2) NOT NULL DEFAULT 0,
    total_effort_minutes   INTEGER NOT NULL DEFAULT 0,
    absence_days           NUMERIC(6,2) NOT NULL DEFAULT 0,
    overtime_hours         NUMERIC(6,2) NOT NULL DEFAULT 0,
    planned_cost_amount    NUMERIC(14,2),
    forecast_cost_amount   NUMERIC(14,2),
    budget_amount          NUMERIC(14,2),
    started_at             TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at             TIMESTAMPTZ,
    CONSTRAINT uq_sprint_seq UNIQUE (project_id, sequence_no),
    CONSTRAINT ck_sprint_status CHECK (status IN ('PLANNED','ACTIVE','COMPLETED','CANCELLED')),
    CONSTRAINT ck_sprint_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);
CREATE INDEX idx_sprints_project_status ON sprints(project_id, status) WHERE deleted_at IS NULL;
-- Only one ACTIVE sprint per project
CREATE UNIQUE INDEX uq_one_active_sprint ON sprints(project_id) WHERE status = 'ACTIVE' AND deleted_at IS NULL;

CREATE TABLE sprint_capacity (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    sprint_id       UUID NOT NULL REFERENCES sprints(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    available_hours NUMERIC(6,2) NOT NULL DEFAULT 0,
    absence_days    NUMERIC(5,2) NOT NULL DEFAULT 0,
    allocation_pct  SMALLINT NOT NULL DEFAULT 100,
    committed_points NUMERIC(6,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_sprint_capacity UNIQUE (sprint_id, user_id)
);

-- ---------- Agile hierarchy -------------------------------------------
CREATE TABLE epics (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    milestone_id    UUID REFERENCES milestones(id) ON DELETE SET NULL,
    item_key        VARCHAR(24) NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    status          VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    priority        VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    color           VARCHAR(16),
    start_date      DATE,
    target_date     DATE,
    rank            VARCHAR(64) NOT NULL DEFAULT 'n',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_epic_key UNIQUE (organization_id, item_key),
    CONSTRAINT ck_epic_status CHECK (status IN ('OPEN','IN_PROGRESS','DONE','CANCELLED'))
);
CREATE INDEX idx_epics_project ON epics(project_id) WHERE deleted_at IS NULL;

CREATE TABLE stories (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id          UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    epic_id             UUID REFERENCES epics(id) ON DELETE SET NULL,
    sprint_id           UUID REFERENCES sprints(id) ON DELETE SET NULL,
    requirement_id      UUID REFERENCES requirements(id) ON DELETE SET NULL,
    workflow_state_id   UUID REFERENCES workflow_states(id),
    item_key            VARCHAR(24) NOT NULL,
    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    acceptance_criteria TEXT,
    type                VARCHAR(16) NOT NULL DEFAULT 'STORY',
    status              VARCHAR(24) NOT NULL DEFAULT 'BACKLOG',
    priority            VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    severity            VARCHAR(16),
    story_points        NUMERIC(5,2),
    ai_story_points     NUMERIC(5,2),
    ai_confidence       NUMERIC(4,3),
    ai_lower_bound      NUMERIC(5,2),
    ai_upper_bound      NUMERIC(5,2),
    ai_prediction_id    UUID,
    estimate_source     VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    business_value      SMALLINT,
    rank                VARCHAR(64) NOT NULL DEFAULT 'n',
    assignee_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    reporter_id         UUID REFERENCES users(id),
    due_date            DATE,
    blocked             BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_reason      VARCHAR(500),
    reopened_count      SMALLINT NOT NULL DEFAULT 0,
    ready_checklist     JSONB NOT NULL DEFAULT '[]'::jsonb,
    done_checklist      JSONB NOT NULL DEFAULT '[]'::jsonb,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_story_key UNIQUE (organization_id, item_key),
    CONSTRAINT ck_story_type CHECK (type IN ('STORY','BUG','SPIKE','CHORE','TECH_DEBT')),
    CONSTRAINT ck_story_status CHECK (status IN ('BACKLOG','READY','IN_PROGRESS','IN_REVIEW','TESTING','DONE','CANCELLED')),
    CONSTRAINT ck_story_priority CHECK (priority IN ('LOWEST','LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_estimate_source CHECK (estimate_source IN ('MANUAL','AI_ACCEPTED','AI_OVERRIDDEN','UNESTIMATED'))
);
CREATE INDEX idx_stories_project_status ON stories(project_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_stories_sprint ON stories(sprint_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_stories_assignee ON stories(assignee_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_stories_backlog_rank ON stories(project_id, rank) WHERE sprint_id IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_stories_title_trgm ON stories USING gin (title gin_trgm_ops);

CREATE TABLE tasks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id        UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    story_id          UUID REFERENCES stories(id) ON DELETE CASCADE,
    parent_task_id    UUID REFERENCES tasks(id) ON DELETE CASCADE,
    sprint_id         UUID REFERENCES sprints(id) ON DELETE SET NULL,
    workflow_state_id UUID REFERENCES workflow_states(id),
    item_key          VARCHAR(24) NOT NULL,
    title             VARCHAR(255) NOT NULL,
    description       TEXT,
    type              VARCHAR(16) NOT NULL DEFAULT 'TASK',
    status            VARCHAR(24) NOT NULL DEFAULT 'TODO',
    priority          VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    severity          VARCHAR(16),
    assignee_id       UUID REFERENCES users(id) ON DELETE SET NULL,
    reporter_id       UUID REFERENCES users(id),
    estimate_minutes  INTEGER,
    spent_minutes     INTEGER NOT NULL DEFAULT 0,
    remaining_minutes INTEGER,
    complexity_score  NUMERIC(4,2),          -- MODEL 3 feature: task complexity
    required_skills   JSONB NOT NULL DEFAULT '[]'::jsonb,
    due_date          DATE,
    rank              VARCHAR(64) NOT NULL DEFAULT 'n',
    blocked           BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_reason    VARCHAR(500),
    blocked_at        TIMESTAMPTZ,
    reopened_count    SMALLINT NOT NULL DEFAULT 0,
    assignment_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL', -- MANUAL | AI_ACCEPTED | AI_OVERRIDDEN
    ai_prediction_id  UUID,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT uq_task_key UNIQUE (organization_id, item_key),
    CONSTRAINT ck_task_status CHECK (status IN ('TODO','IN_PROGRESS','IN_REVIEW','BLOCKED','DONE','CANCELLED')),
    CONSTRAINT ck_task_priority CHECK (priority IN ('LOWEST','LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_task_assignment_source CHECK (assignment_source IN ('MANUAL','AI_ACCEPTED','AI_OVERRIDDEN'))
);
CREATE INDEX idx_tasks_story ON tasks(story_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_assignee_status ON tasks(assignee_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_sprint ON tasks(sprint_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_parent ON tasks(parent_task_id);

-- ---------- Cross-cutting item metadata --------------------------------
CREATE TABLE labels (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      UUID REFERENCES projects(id) ON DELETE CASCADE,
    name            VARCHAR(60) NOT NULL,
    color           VARCHAR(16) NOT NULL DEFAULT '#64748b',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_label_name UNIQUE (organization_id, project_id, name)
);

CREATE TABLE item_labels (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    label_id        UUID NOT NULL REFERENCES labels(id) ON DELETE CASCADE,
    entity_type     VARCHAR(20) NOT NULL,   -- STORY | TASK | EPIC
    entity_id       UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_item_label UNIQUE (label_id, entity_type, entity_id)
);
CREATE INDEX idx_item_labels_entity ON item_labels(entity_type, entity_id);

CREATE TABLE item_dependencies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    source_type     VARCHAR(20) NOT NULL,
    source_id       UUID NOT NULL,
    target_type     VARCHAR(20) NOT NULL,
    target_id       UUID NOT NULL,
    dependency_type VARCHAR(20) NOT NULL DEFAULT 'BLOCKS',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    CONSTRAINT uq_dependency UNIQUE (source_type, source_id, target_type, target_id, dependency_type),
    CONSTRAINT ck_dependency_type CHECK (dependency_type IN ('BLOCKS','BLOCKED_BY','RELATES_TO','DUPLICATES')),
    CONSTRAINT ck_no_self_dependency CHECK (NOT (source_id = target_id AND source_type = target_type))
);
CREATE INDEX idx_dependencies_source ON item_dependencies(source_type, source_id);
CREATE INDEX idx_dependencies_target ON item_dependencies(target_type, target_id);

CREATE TABLE watchers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    entity_type     VARCHAR(20) NOT NULL,
    entity_id       UUID NOT NULL,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_watcher UNIQUE (entity_type, entity_id, user_id)
);
CREATE INDEX idx_watchers_user ON watchers(user_id);

-- ---------- Time tracking ----------------------------------------------
CREATE TABLE time_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    sprint_id       UUID REFERENCES sprints(id) ON DELETE SET NULL,
    entity_type     VARCHAR(20) NOT NULL DEFAULT 'TASK',
    entity_id       UUID NOT NULL,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    minutes         INTEGER NOT NULL,
    is_overtime     BOOLEAN NOT NULL DEFAULT FALSE,
    description     VARCHAR(500),
    logged_on       DATE NOT NULL DEFAULT CURRENT_DATE,
    started_at      TIMESTAMPTZ,
    stopped_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_time_minutes CHECK (minutes > 0)
);
CREATE INDEX idx_time_entries_user_date ON time_entries(user_id, logged_on);
CREATE INDEX idx_time_entries_entity ON time_entries(entity_type, entity_id);
CREATE INDEX idx_time_entries_sprint ON time_entries(sprint_id);

CREATE TABLE retrospectives (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    sprint_id       UUID NOT NULL REFERENCES sprints(id) ON DELETE CASCADE,
    went_well       JSONB NOT NULL DEFAULT '[]'::jsonb,
    went_wrong      JSONB NOT NULL DEFAULT '[]'::jsonb,
    action_items    JSONB NOT NULL DEFAULT '[]'::jsonb,
    team_mood       SMALLINT,
    summary         TEXT,
    facilitator_id  UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_retro_sprint UNIQUE (sprint_id)
);
