-- =====================================================================
-- V1 · Identity, tenancy and RBAC
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------- Global user identity -------------------------------------
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL,
    email_normalized    VARCHAR(255) NOT NULL,
    password_hash       VARCHAR(255),
    full_name           VARCHAR(150) NOT NULL,
    avatar_url          VARCHAR(512),
    job_title           VARCHAR(120),
    timezone            VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    locale              VARCHAR(16)  NOT NULL DEFAULT 'en',
    status              VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    email_verified_at   TIMESTAMPTZ,
    mfa_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    mfa_secret          VARCHAR(255),
    mfa_recovery_codes  JSONB,
    failed_login_count  SMALLINT     NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email_normalized),
    CONSTRAINT ck_users_status CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','DEACTIVATED'))
);
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_name_trgm ON users USING gin (full_name gin_trgm_ops);

-- ---------- Tenant root ----------------------------------------------
CREATE TABLE organizations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(150) NOT NULL,
    slug                VARCHAR(80)  NOT NULL,
    logo_url            VARCHAR(512),
    plan                VARCHAR(24)  NOT NULL DEFAULT 'FREE',
    status              VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    currency            CHAR(3)      NOT NULL DEFAULT 'USD',
    timezone            VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    working_days        SMALLINT     NOT NULL DEFAULT 5,
    default_hourly_rate NUMERIC(10,2) NOT NULL DEFAULT 0,
    owner_user_id       UUID         REFERENCES users(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID, updated_by UUID,
    version             BIGINT       NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_org_slug UNIQUE (slug),
    CONSTRAINT ck_org_plan CHECK (plan IN ('FREE','STARTER','PRO','ENTERPRISE')),
    CONSTRAINT ck_org_status CHECK (status IN ('ACTIVE','SUSPENDED','CANCELLED'))
);

CREATE TABLE workspaces (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(150) NOT NULL,
    workspace_key   VARCHAR(16)  NOT NULL,
    description     TEXT,
    icon            VARCHAR(64),
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_workspace_key UNIQUE (organization_id, workspace_key)
);
CREATE INDEX idx_workspaces_org ON workspaces(organization_id);

-- ---------- RBAC ------------------------------------------------------
CREATE TABLE permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(80)  NOT NULL UNIQUE,   -- e.g. 'project:update'
    resource    VARCHAR(40)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE roles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE, -- NULL => system role
    name            VARCHAR(60)  NOT NULL,
    code            VARCHAR(40)  NOT NULL,
    description     VARCHAR(255),
    system_role     BOOLEAN      NOT NULL DEFAULT FALSE,
    rank            SMALLINT     NOT NULL DEFAULT 100,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID,
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_roles_org_code ON roles(COALESCE(organization_id,'00000000-0000-0000-0000-000000000000'::uuid), code);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE org_members (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id         UUID NOT NULL REFERENCES roles(id),
    status          VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    invited_by      UUID REFERENCES users(id),
    joined_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_org_member UNIQUE (organization_id, user_id),
    CONSTRAINT ck_org_member_status CHECK (status IN ('ACTIVE','INVITED','SUSPENDED','REMOVED'))
);
CREATE INDEX idx_org_members_user ON org_members(user_id);

CREATE TABLE workspace_members (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id         UUID REFERENCES roles(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_workspace_member UNIQUE (workspace_id, user_id)
);
CREATE INDEX idx_workspace_members_org ON workspace_members(organization_id);

CREATE TABLE invitations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workspace_id    UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    email           VARCHAR(255) NOT NULL,
    role_id         UUID NOT NULL REFERENCES roles(id),
    token_hash      VARCHAR(128) NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMPTZ  NOT NULL,
    accepted_at     TIMESTAMPTZ,
    invited_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID, updated_by UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING','ACCEPTED','REVOKED','EXPIRED'))
);
CREATE UNIQUE INDEX uq_invitation_token ON invitations(token_hash);
CREATE INDEX idx_invitations_org_email ON invitations(organization_id, email);

-- ---------- Tokens ----------------------------------------------------
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
    token_hash      VARCHAR(128) NOT NULL,
    family_id       UUID NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by     UUID,
    user_agent      VARCHAR(255),
    ip_address      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_refresh_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);

CREATE TABLE one_time_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     VARCHAR(32)  NOT NULL,   -- PASSWORD_RESET | EMAIL_VERIFICATION | MFA_CHALLENGE
    token_hash  VARCHAR(128) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ott_purpose CHECK (purpose IN ('PASSWORD_RESET','EMAIL_VERIFICATION','MFA_CHALLENGE'))
);
CREATE UNIQUE INDEX uq_ott_hash ON one_time_tokens(token_hash);
CREATE INDEX idx_ott_user_purpose ON one_time_tokens(user_id, purpose);
