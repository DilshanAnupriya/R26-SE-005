-- =====================================================================
-- V7 · Align the governance and collaboration tables with BaseEntity
--
-- Same reasoning as V6: every mutable tenant-scoped table extends
-- TenantAwareEntity, which requires the full auditing / optimistic-locking
-- set. These three shipped without it. Backfilling keeps one entity
-- superclass for the whole schema rather than a growing list of tables
-- that "are like the others except for two columns".
--
-- The append-only tables (ai_predictions, ai_feedback, risk_assessments,
-- resource_allocation_suggestions, cost_forecasts, activity_logs,
-- audit_logs, notifications) are deliberately left alone. They extend
-- AppendOnlyTenantEntity, which maps id + organization_id + created_at and
-- nothing else, because a row that records what happened must not carry an
-- update path.
-- =====================================================================

ALTER TABLE feature_flags
    ADD COLUMN created_by UUID;

ALTER TABLE workspace_settings
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN created_by UUID;

ALTER TABLE notification_preferences
    ADD COLUMN created_by UUID,
    ADD COLUMN updated_by UUID;

-- ---------------------------------------------------------------------
-- Feature flags are read on the hot path of every AI call: the gate runs
-- before the model does. The unique constraint covers (organization_id,
-- workspace_id, flag_key), but workspace_id is nullable, and in Postgres
-- NULLs are distinct — so the org-level fallback row is not reachable
-- through that index. This one is.
-- ---------------------------------------------------------------------
CREATE INDEX idx_feature_flags_lookup ON feature_flags(organization_id, flag_key);

CREATE UNIQUE INDEX uq_feature_flag_org_default
    ON feature_flags(organization_id, flag_key)
    WHERE workspace_id IS NULL;

-- ---------------------------------------------------------------------
-- Prediction reads are almost always "the latest one for this entity".
-- idx_predictions_entity covers it, but only after the model code has been
-- filtered — and a story carries predictions from two different models.
-- ---------------------------------------------------------------------
CREATE INDEX idx_predictions_model_entity
    ON ai_predictions(model_code, entity_type, entity_id, created_at DESC);

-- Cost trend and risk summary both scan by project.
CREATE INDEX idx_cost_project ON cost_forecasts(project_id, snapshot_at DESC);
CREATE INDEX idx_risk_project ON risk_assessments(project_id, created_at DESC);
