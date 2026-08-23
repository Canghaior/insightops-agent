CREATE TABLE agent_workflow_template (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    category VARCHAR(48) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, name)
);

CREATE INDEX idx_agent_workflow_template_workspace_updated
    ON agent_workflow_template (workspace_id, updated_at DESC);

CREATE TABLE agent_workflow_template_version (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES agent_workflow_template(id) ON DELETE CASCADE,
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    summary VARCHAR(500) NOT NULL DEFAULT '',
    entry_question VARCHAR(4000) NOT NULL,
    graph_spec JSONB NOT NULL,
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    UNIQUE (template_id, version)
);

CREATE INDEX idx_agent_workflow_version_template_version
    ON agent_workflow_template_version (template_id, version DESC);

ALTER TABLE agent_workflow_template
    ADD COLUMN active_version_id UUID
        REFERENCES agent_workflow_template_version(id) ON DELETE SET NULL;

CREATE TABLE agent_workflow_activation_audit (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    template_id UUID NOT NULL REFERENCES agent_workflow_template(id) ON DELETE CASCADE,
    previous_version_id UUID REFERENCES agent_workflow_template_version(id) ON DELETE SET NULL,
    activated_version_id UUID NOT NULL REFERENCES agent_workflow_template_version(id),
    activated_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_agent_workflow_activation_workspace_created
    ON agent_workflow_activation_audit (workspace_id, created_at DESC);
