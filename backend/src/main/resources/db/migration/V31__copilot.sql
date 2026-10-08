-- =====================================================================
-- SprintIQ Copilot: saved conversations with the LLM assistant, and the
-- changes it proposes for a person to confirm.
-- =====================================================================

-- One user's thread. Private to that user: a colleague with the same role still
-- cannot read it, because what someone asks an assistant is not team data.
CREATE TABLE copilot_conversations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title             VARCHAR(80) NOT NULL DEFAULT 'New conversation',

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_copilot_conversations_owner
    ON copilot_conversations(organization_id, user_id, updated_at DESC);

-- Only the two visible roles are stored. Tool exchanges are not: their results go
-- stale, and replaying a week-old risk score as if it were current is exactly the
-- mistake the assistant is told never to make. What was called is kept in
-- tool_steps for display.
CREATE TABLE copilot_messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    conversation_id   UUID NOT NULL REFERENCES copilot_conversations(id) ON DELETE CASCADE,
    role              VARCHAR(16) NOT NULL,
    content           TEXT NOT NULL,
    model             VARCHAR(120),
    tool_steps        JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_copilot_message_role CHECK (role IN ('USER','ASSISTANT'))
);
CREATE INDEX idx_copilot_messages_conversation ON copilot_messages(conversation_id, created_at);

-- A change the assistant would like to make. Nothing happens until a person
-- confirms it; the summary is written by the server from the payload, never by
-- the model, so what the button says is what the button does.
CREATE TABLE copilot_proposals (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    conversation_id   UUID NOT NULL REFERENCES copilot_conversations(id) ON DELETE CASCADE,
    message_id        UUID REFERENCES copilot_messages(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type              VARCHAR(24) NOT NULL,
    payload           JSONB NOT NULL,
    summary           VARCHAR(500) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expires_at        TIMESTAMPTZ NOT NULL,
    resolved_at       TIMESTAMPTZ,
    resolved_by       UUID REFERENCES users(id) ON DELETE SET NULL,
    result_message    VARCHAR(500),

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID, updated_by UUID, version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_copilot_proposal_type
        CHECK (type IN ('ACCEPT_ESTIMATE','ASSIGN_TASK','MOVE_TO_SPRINT')),
    CONSTRAINT ck_copilot_proposal_status
        CHECK (status IN ('PENDING','CONFIRMED','REJECTED','FAILED','EXPIRED'))
);
CREATE INDEX idx_copilot_proposals_conversation ON copilot_proposals(conversation_id);
CREATE INDEX idx_copilot_proposals_message ON copilot_proposals(message_id);
