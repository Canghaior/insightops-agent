CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_app_user_username_lower ON app_user (lower(username));

CREATE TABLE user_credential (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    password_hash VARCHAR(100) NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    password_changed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_member (
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(24) NOT NULL DEFAULT 'OWNER'
        CHECK (role IN ('OWNER', 'MEMBER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_auth_session_active
    ON auth_session (token_hash, expires_at) WHERE revoked_at IS NULL;

INSERT INTO app_user (id, username, display_name, status)
VALUES (
    '00000000-0000-0000-0000-000000000101',
    'alpha-owner',
    'Alpha Owner',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO workspace_member (workspace_id, user_id, role)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'OWNER'
)
ON CONFLICT DO NOTHING;

ALTER TABLE conversation_session
    ADD COLUMN owner_user_id UUID REFERENCES app_user(id);

UPDATE conversation_session
SET owner_user_id = '00000000-0000-0000-0000-000000000101'
WHERE owner_user_id IS NULL;

ALTER TABLE conversation_session
    ALTER COLUMN owner_user_id SET NOT NULL;

CREATE INDEX idx_conversation_owner_updated
    ON conversation_session (owner_user_id, status, updated_at DESC);

ALTER TABLE agent_run
    ADD COLUMN owner_user_id UUID REFERENCES app_user(id);

UPDATE agent_run
SET owner_user_id = '00000000-0000-0000-0000-000000000101'
WHERE owner_user_id IS NULL;

ALTER TABLE agent_run
    ALTER COLUMN owner_user_id SET NOT NULL;

CREATE INDEX idx_agent_run_owner_created
    ON agent_run (owner_user_id, workspace_id, created_at DESC);

CREATE TABLE user_memory (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    memory_key VARCHAR(80) NOT NULL,
    memory_value VARCHAR(1000) NOT NULL,
    category VARCHAR(24) NOT NULL
        CHECK (category IN ('PROFILE', 'PREFERENCE', 'INTEREST', 'CONSTRAINT')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, workspace_id, memory_key)
);

CREATE INDEX idx_user_memory_active
    ON user_memory (user_id, workspace_id, updated_at DESC) WHERE enabled = TRUE;

CREATE TABLE user_project_watch (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES tracked_project(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, project_id)
);

INSERT INTO user_project_watch (user_id, workspace_id, project_id, enabled)
SELECT
    '00000000-0000-0000-0000-000000000101',
    project.workspace_id,
    project.id,
    TRUE
FROM tracked_project project
WHERE project.workspace_id = '00000000-0000-0000-0000-000000000001'
ON CONFLICT DO NOTHING;
