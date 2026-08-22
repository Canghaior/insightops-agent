CREATE TABLE agent_release_candidate (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('DRAFT', 'PASSED', 'FAILED', 'ACTIVE', 'RETIRED')),
    planner_prompt_appendix TEXT NOT NULL DEFAULT '',
    model_name VARCHAR(128) NOT NULL,
    temperature NUMERIC(4, 3) NOT NULL CHECK (temperature BETWEEN 0 AND 2),
    max_output_tokens INTEGER NOT NULL CHECK (max_output_tokens BETWEEN 1 AND 8192),
    tool_contract_hash VARCHAR(64) NOT NULL,
    based_on_id UUID REFERENCES agent_release_candidate(id) ON DELETE SET NULL,
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    evaluated_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    UNIQUE (workspace_id, version)
);

CREATE INDEX idx_agent_release_candidate_workspace_created
    ON agent_release_candidate (workspace_id, created_at DESC);

CREATE TABLE agent_runtime_release (
    workspace_id UUID PRIMARY KEY REFERENCES workspace(id) ON DELETE CASCADE,
    active_candidate_id UUID NOT NULL REFERENCES agent_release_candidate(id),
    version INTEGER NOT NULL CHECK (version > 0),
    updated_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE agent_release_activation_audit (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    previous_candidate_id UUID REFERENCES agent_release_candidate(id) ON DELETE SET NULL,
    activated_candidate_id UUID NOT NULL REFERENCES agent_release_candidate(id),
    activated_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_agent_release_activation_workspace_created
    ON agent_release_activation_audit (workspace_id, created_at DESC);
