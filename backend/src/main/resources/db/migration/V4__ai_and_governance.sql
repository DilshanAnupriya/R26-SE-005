-- =====================================================================
-- V4 · AI model registry, predictions, derived AI artefacts, governance
-- =====================================================================
CREATE TABLE ai_models (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code              VARCHAR(48) NOT NULL UNIQUE,   -- M1_STORY_POINTS, M2_OVER_BUDGET, ...
    name              VARCHAR(120) NOT NULL,
    description       TEXT,
    version           VARCHAR(24) NOT NULL DEFAULT '1.0.0',
    task_type         VARCHAR(24) NOT NULL,          -- REGRESSION | CLASSIFICATION | RANKING
    endpoint_url      VARCHAR(512),
    default_mode      VARCHAR(20) NOT NULL DEFAULT 'ZERO_SHOT',
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    latency_budget_ms INTEGER NOT NULL DEFAULT 4000,
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version_lock      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_ai_mode CHECK (default_mode IN ('ZERO_SHOT','FEW_SHOT','FINE_TUNED'))
);

CREATE TABLE ai_predictions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id        UUID REFERENCES projects(id) ON DELETE CASCADE,
    model_code        VARCHAR(48) NOT NULL,
    model_version     VARCHAR(24) NOT NULL,
    mode              VARCHAR(20) NOT NULL DEFAULT 'ZERO_SHOT',
    entity_type       VARCHAR(24) NOT NULL,      -- STORY | SPRINT | TASK | PROJECT
    entity_id         UUID,
    request_payload   JSONB NOT NULL,            -- exact feature vector, for retraining
    predicted_value   NUMERIC(14,4),
    predicted_label   VARCHAR(48),
    confidence        NUMERIC(4,3),
    lower_bound       NUMERIC(14,4),
    upper_bound       NUMERIC(14,4),
    drivers           JSONB NOT NULL DEFAULT '[]'::jsonb, -- [{feature,contribution,direction}]
    explanation       VARCHAR(1000),
    raw_response      JSONB,
    status            VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    error_message     VARCHAR(500),
    latency_ms        INTEGER,
    cache_hit         BOOLEAN NOT NULL DEFAULT FALSE,
    feature_hash      VARCHAR(64),
    created_by        UUID REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_prediction_status CHECK (status IN ('SUCCESS','FAILED','DEGRADED','TIMEOUT'))
);
CREATE INDEX idx_predictions_entity ON ai_predictions(entity_type, entity_id, created_at DESC);
CREATE INDEX idx_predictions_org_model ON ai_predictions(organization_id, model_code, created_at DESC);
CREATE INDEX idx_predictions_feature_hash ON ai_predictions(feature_hash);

CREATE TABLE ai_feedback (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    prediction_id   UUID NOT NULL REFERENCES ai_predictions(id) ON DELETE CASCADE,
    action          VARCHAR(20) NOT NULL,   -- ACCEPTED | OVERRIDDEN | DISMISSED
    predicted_value NUMERIC(14,4),
    final_value     NUMERIC(14,4),
    delta           NUMERIC(14,4),
    reason          VARCHAR(500),
    user_id         UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_feedback_action CHECK (action IN ('ACCEPTED','OVERRIDDEN','DISMISSED'))
);
CREATE INDEX idx_feedback_prediction ON ai_feedback(prediction_id);

-- Daily snapshot powering burndown, velocity and MODEL 2 features
CREATE TABLE sprint_metrics (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    sprint_id             UUID NOT NULL REFERENCES sprints(id) ON DELETE CASCADE,
    snapshot_date         DATE NOT NULL,
    committed_points      NUMERIC(8,2) NOT NULL DEFAULT 0,
    completed_points      NUMERIC(8,2) NOT NULL DEFAULT 0,
    remaining_points      NUMERIC(8,2) NOT NULL DEFAULT 0,
    scope_added_points    NUMERIC(8,2) NOT NULL DEFAULT 0,
    scope_removed_points  NUMERIC(8,2) NOT NULL DEFAULT 0,
    ideal_remaining       NUMERIC(8,2),
    velocity              NUMERIC(8,2),
    blocked_count         SMALLINT NOT NULL DEFAULT 0,
    reopened_count        SMALLINT NOT NULL DEFAULT 0,
    defect_count          SMALLINT NOT NULL DEFAULT 0,
    requirement_changes   SMALLINT NOT NULL DEFAULT 0,
    scope_volatility      NUMERIC(6,4) NOT NULL DEFAULT 0,
    workload_index        NUMERIC(6,3),
    effort_minutes        INTEGER NOT NULL DEFAULT 0,
    active_contributors   SMALLINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sprint_metric_day UNIQUE (sprint_id, snapshot_date)
);
CREATE INDEX idx_sprint_metrics_sprint ON sprint_metrics(sprint_id, snapshot_date);

-- MODEL 2 output
CREATE TABLE risk_assessments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      UUID REFERENCES projects(id) ON DELETE CASCADE,
    sprint_id       UUID NOT NULL REFERENCES sprints(id) ON DELETE CASCADE,
    risk_type       VARCHAR(32) NOT NULL,        -- OVER_BUDGET | REQUIREMENT_CHANGE
    risk_score      NUMERIC(4,3) NOT NULL,
    risk_label      BOOLEAN NOT NULL,
    severity        VARCHAR(16) NOT NULL DEFAULT 'LOW',
    confidence      NUMERIC(4,3),
    drivers         JSONB NOT NULL DEFAULT '[]'::jsonb,
    mitigations     JSONB NOT NULL DEFAULT '[]'::jsonb,
    explanation     VARCHAR(1000),
    prediction_id   UUID REFERENCES ai_predictions(id) ON DELETE SET NULL,
    acknowledged_by UUID REFERENCES users(id),
    acknowledged_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_risk_type CHECK (risk_type IN ('OVER_BUDGET','REQUIREMENT_CHANGE')),
    CONSTRAINT ck_risk_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL'))
);
CREATE INDEX idx_risk_sprint ON risk_assessments(sprint_id, risk_type, created_at DESC);

-- MODEL 3 output
CREATE TABLE resource_allocation_suggestions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id             UUID REFERENCES projects(id) ON DELETE CASCADE,
    task_id                UUID REFERENCES tasks(id) ON DELETE CASCADE,
    story_id               UUID REFERENCES stories(id) ON DELETE CASCADE,
    candidate_user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rank_position          SMALLINT NOT NULL,
    fit_score              NUMERIC(4,3) NOT NULL,
    predicted_performance  NUMERIC(6,3),
    consistency_score      NUMERIC(4,3),
    learning_rate          NUMERIC(5,4),
    skill_match_ratio      NUMERIC(4,3),
    current_load_pct       NUMERIC(5,2),
    overload_risk          BOOLEAN NOT NULL DEFAULT FALSE,
    reasoning              JSONB NOT NULL DEFAULT '[]'::jsonb,
    explanation            VARCHAR(1000),
    prediction_id          UUID REFERENCES ai_predictions(id) ON DELETE SET NULL,
    chosen                 BOOLEAN NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_alloc_target CHECK (task_id IS NOT NULL OR story_id IS NOT NULL)
);
CREATE INDEX idx_alloc_task ON resource_allocation_suggestions(task_id, rank_position);

-- MODEL 4 output
CREATE TABLE cost_forecasts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id            UUID REFERENCES projects(id) ON DELETE CASCADE,
    sprint_id             UUID NOT NULL REFERENCES sprints(id) ON DELETE CASCADE,
    stage                 VARCHAR(20) NOT NULL,      -- INITIAL | MID_SPRINT
    forecast_amount       NUMERIC(14,2) NOT NULL,
    error_margin_amount   NUMERIC(14,2),
    lower_bound           NUMERIC(14,2),
    upper_bound           NUMERIC(14,2),
    currency              CHAR(3) NOT NULL DEFAULT 'USD',
    confidence            NUMERIC(4,3),
    trend                 VARCHAR(10),               -- UP | FLAT | DOWN
    variance_vs_budget_pct NUMERIC(6,2),
    inputs                JSONB NOT NULL DEFAULT '{}'::jsonb,
    drivers               JSONB NOT NULL DEFAULT '[]'::jsonb,
    explanation           VARCHAR(1000),
    prediction_id         UUID REFERENCES ai_predictions(id) ON DELETE SET NULL,
    snapshot_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cost_stage CHECK (stage IN ('INITIAL','MID_SPRINT')),
    CONSTRAINT ck_cost_trend CHECK (trend IS NULL OR trend IN ('UP','FLAT','DOWN'))
);
CREATE INDEX idx_cost_sprint ON cost_forecasts(sprint_id, stage, snapshot_at DESC);

-- ---------- Governance -------------------------------------------------
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
    actor_user_id   UUID REFERENCES users(id),
    actor_email     VARCHAR(255),
    action          VARCHAR(64) NOT NULL,
    entity_type     VARCHAR(48),
    entity_id       UUID,
    before_state    JSONB,
    after_state     JSONB,
    outcome         VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(255),
    correlation_id  VARCHAR(64),
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS','FAILURE','DENIED'))
);
CREATE INDEX idx_audit_org_time ON audit_logs(organization_id, created_at DESC);
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_actor ON audit_logs(actor_user_id, created_at DESC);

CREATE TABLE workspace_settings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    setting_key     VARCHAR(80) NOT NULL,
    setting_value   JSONB NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_workspace_setting UNIQUE (workspace_id, setting_key)
);

CREATE TABLE feature_flags (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workspace_id    UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    flag_key        VARCHAR(80) NOT NULL,      -- ai.story_points, ai.risk, ai.allocation, ai.cost
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_feature_flag UNIQUE (organization_id, workspace_id, flag_key)
);

CREATE TABLE integrations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workspace_id    UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    provider        VARCHAR(48) NOT NULL,      -- SLACK | GITHUB | JIRA_IMPORT | WEBHOOK
    display_name    VARCHAR(120),
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    secret_ref      VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    last_sync_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_integration_status CHECK (status IN ('ACTIVE','INACTIVE','ERROR'))
);
