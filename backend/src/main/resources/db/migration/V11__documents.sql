-- =====================================================================
-- V11 · Documents
--
-- A tree of folders and documents, with every edit kept.
--
-- The versions table is the point. A wiki without history is a place where
-- somebody's careful page becomes somebody else's careless paste and there is
-- no way back — which is exactly why teams stop trusting internal docs and go
-- back to sending each other files.
-- =====================================================================

CREATE TABLE documents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    -- Nullable: an organization-wide handbook belongs to no project.
    project_id        UUID REFERENCES projects(id) ON DELETE CASCADE,
    -- Self-referencing, so folders nest. Depth is bounded in the service rather
    -- than here; a check constraint cannot walk a chain.
    parent_id         UUID REFERENCES documents(id) ON DELETE CASCADE,

    kind              VARCHAR(16) NOT NULL DEFAULT 'DOCUMENT',
    title             VARCHAR(255) NOT NULL,
    -- Markdown. Stored as text rather than parsed structure: the day someone
    -- wants a different renderer, the content survives the change.
    content           TEXT,
    status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT',

    -- Ordering within a folder. Documents are arranged by hand — the order is
    -- an argument about what to read first, not a sort key.
    position          SMALLINT NOT NULL DEFAULT 0,
    -- Incremented on every content change; the head version number.
    version_no        INTEGER NOT NULL DEFAULT 1,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,
    deleted_at        TIMESTAMPTZ,

    CONSTRAINT ck_document_kind CHECK (kind IN ('FOLDER','DOCUMENT')),
    CONSTRAINT ck_document_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    CONSTRAINT ck_document_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id),
    -- A folder holds documents; it has no body of its own. Without this the two
    -- kinds drift into one and every renderer has to guess.
    CONSTRAINT ck_folder_has_no_content CHECK (kind <> 'FOLDER' OR content IS NULL)
);
CREATE INDEX idx_documents_org ON documents(organization_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_documents_parent ON documents(parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_documents_project ON documents(project_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- Every saved edit, append-only.
--
-- The title is stored alongside the content because a rename is an edit too,
-- and a history that only tracks bodies cannot explain why a page nobody can
-- find used to be called something else.
-- ---------------------------------------------------------------------
CREATE TABLE document_versions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    document_id       UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,

    version_no        INTEGER NOT NULL,
    title             VARCHAR(255) NOT NULL,
    content           TEXT,
    -- What changed, in the author's words. Optional, because forcing a message
    -- produces "update" on every row and teaches everyone to ignore the column.
    summary           VARCHAR(500),

    authored_by       UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_document_version UNIQUE (document_id, version_no)
);
CREATE INDEX idx_document_versions ON document_versions(document_id, version_no DESC);

-- ---------------------------------------------------------------------
-- Permissions. Reading comes with the general view grant; writing is not an
-- admin act — documentation that only managers can edit does not get written.
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, resource, action, description) VALUES
 ('document:read','document','read','View documents'),
 ('document:write','document','write','Create and edit documents'),
 ('document:delete','document','delete','Delete documents and folders');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('OWNER','ADMIN','MANAGER')
  AND p.code IN ('document:read','document:write','document:delete');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'MEMBER' AND p.code IN ('document:read','document:write');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'VIEWER' AND p.code = 'document:read';
