-- =====================================================================
-- V8 · Asynchronous report exports
--
-- A table rather than an in-memory map. An export that survives a
-- restart is the difference between "your download is ready" and a
-- spinner that never resolves, and a multi-node deployment cannot poll
-- a job that lives in one node's heap.
--
-- The generated file goes to the same storage attachments use; only its
-- key is recorded here. A CSV of forty thousand rows in a BYTEA column
-- is a row every backup, replica and sequential scan pays for.
-- =====================================================================
CREATE TABLE export_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    requested_by    UUID NOT NULL REFERENCES users(id),
    report          VARCHAR(48) NOT NULL,
    format          VARCHAR(12) NOT NULL DEFAULT 'CSV',
    project_id      UUID REFERENCES projects(id) ON DELETE CASCADE,
    sprint_id       UUID REFERENCES sprints(id) ON DELETE CASCADE,
    parameters      JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    storage_key     VARCHAR(512),
    file_name       VARCHAR(255),
    size_bytes      BIGINT,
    row_count       INTEGER,
    error_message   VARCHAR(500),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    -- Exports are a snapshot of a moment, not a living document. After this
    -- they are purged: the underlying data has moved on, and a stale CSV that
    -- still downloads is worse than one that admits it expired.
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_export_status CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','EXPIRED')),
    CONSTRAINT ck_export_format CHECK (format IN ('CSV'))
);

CREATE INDEX idx_export_jobs_requester ON export_jobs(requested_by, created_at DESC);
CREATE INDEX idx_export_jobs_org ON export_jobs(organization_id, created_at DESC);

-- The purge job scans for expired rows; without this it seq-scans the table.
CREATE INDEX idx_export_jobs_expiry ON export_jobs(expires_at)
    WHERE status = 'COMPLETED';
