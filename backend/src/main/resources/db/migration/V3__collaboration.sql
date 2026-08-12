-- =====================================================================
-- V3 · Comments, activity, notifications, attachments
-- =====================================================================
CREATE TABLE comments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id        UUID REFERENCES projects(id) ON DELETE CASCADE,
    entity_type       VARCHAR(20) NOT NULL,     -- STORY | TASK | EPIC | SPRINT | PROJECT
    entity_id         UUID NOT NULL,
    parent_comment_id UUID REFERENCES comments(id) ON DELETE CASCADE,
    author_id         UUID NOT NULL REFERENCES users(id),
    body              TEXT NOT NULL,
    body_html         TEXT,
    mentions          JSONB NOT NULL DEFAULT '[]'::jsonb,
    edited_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at        TIMESTAMPTZ
);
CREATE INDEX idx_comments_entity ON comments(entity_type, entity_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_comments_author ON comments(author_id);

CREATE TABLE activity_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      UUID REFERENCES projects(id) ON DELETE CASCADE,
    entity_type     VARCHAR(24) NOT NULL,
    entity_id       UUID NOT NULL,
    entity_key      VARCHAR(24),
    actor_id        UUID REFERENCES users(id),
    action          VARCHAR(48) NOT NULL,   -- CREATED | UPDATED | STATUS_CHANGED | ASSIGNED | ...
    field_name      VARCHAR(60),
    old_value       TEXT,
    new_value       TEXT,
    summary         VARCHAR(500),
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_activity_entity ON activity_logs(entity_type, entity_id, created_at DESC);
CREATE INDEX idx_activity_project ON activity_logs(project_id, created_at DESC);
CREATE INDEX idx_activity_actor ON activity_logs(actor_id, created_at DESC);

CREATE TABLE notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    recipient_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_id        UUID REFERENCES users(id),
    type            VARCHAR(48) NOT NULL,   -- MENTION | ASSIGNED | COMMENT | SPRINT_RISK | COST_ALERT | ...
    title           VARCHAR(200) NOT NULL,
    body            VARCHAR(1000),
    entity_type     VARCHAR(24),
    entity_id       UUID,
    link            VARCHAR(500),
    priority        VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    read_at         TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_priority CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT'))
);
CREATE INDEX idx_notifications_recipient ON notifications(recipient_id, read_at, created_at DESC);

CREATE TABLE notification_preferences (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(48) NOT NULL,
    in_app          BOOLEAN NOT NULL DEFAULT TRUE,
    email           BOOLEAN NOT NULL DEFAULT TRUE,
    digest          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_notification_pref UNIQUE (user_id, organization_id, notification_type)
);

CREATE TABLE attachments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id        UUID REFERENCES projects(id) ON DELETE CASCADE,
    entity_type       VARCHAR(20) NOT NULL,
    entity_id         UUID NOT NULL,
    file_name         VARCHAR(255) NOT NULL,
    storage_key       VARCHAR(512) NOT NULL,
    content_type      VARCHAR(120) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    checksum_sha256   VARCHAR(64),
    scan_status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    uploaded_by       UUID NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT ck_scan_status CHECK (scan_status IN ('PENDING','CLEAN','INFECTED','SKIPPED'))
);
CREATE INDEX idx_attachments_entity ON attachments(entity_type, entity_id) WHERE deleted_at IS NULL;
