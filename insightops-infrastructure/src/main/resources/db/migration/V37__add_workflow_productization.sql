CREATE TABLE agent_workflow_parameter_preset (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    template_id UUID NOT NULL REFERENCES agent_workflow_template(id) ON DELETE CASCADE,
    template_version_id UUID NOT NULL REFERENCES agent_workflow_template_version(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    values_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (owner_user_id, template_version_id, name)
);

CREATE INDEX idx_agent_workflow_preset_owner_version
    ON agent_workflow_parameter_preset
        (workspace_id, owner_user_id, template_version_id, updated_at DESC);

CREATE TABLE agent_workflow_template_share (
    id UUID PRIMARY KEY,
    source_workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    template_id UUID NOT NULL REFERENCES agent_workflow_template(id) ON DELETE CASCADE,
    template_version_id UUID NOT NULL REFERENCES agent_workflow_template_version(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    import_count INTEGER NOT NULL DEFAULT 0 CHECK (import_count >= 0),
    last_imported_at TIMESTAMPTZ
);

CREATE INDEX idx_agent_workflow_share_template_created
    ON agent_workflow_template_share (source_workspace_id, template_id, created_at DESC);

CREATE INDEX idx_agent_workflow_share_active_expiry
    ON agent_workflow_template_share (token_hash, expires_at)
    WHERE status = 'ACTIVE';
