CREATE TABLE agent_workflow_run (
    run_id UUID PRIMARY KEY REFERENCES agent_run(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    template_id UUID REFERENCES agent_workflow_template(id) ON DELETE SET NULL,
    template_version_id UUID REFERENCES agent_workflow_template_version(id) ON DELETE SET NULL,
    template_name_snapshot VARCHAR(128) NOT NULL,
    template_version_snapshot INTEGER NOT NULL CHECK (template_version_snapshot > 0),
    entry_question_snapshot TEXT NOT NULL,
    graph_spec_snapshot JSONB NOT NULL,
    input_snapshot JSONB NOT NULL,
    tool_contract_fingerprint VARCHAR(64) NOT NULL,
    request_id UUID NOT NULL,
    source_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    retry_root_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    retry_from_node_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, owner_user_id, request_id)
);

CREATE INDEX idx_agent_workflow_run_template_created
    ON agent_workflow_run (workspace_id, template_id, created_at DESC);

CREATE INDEX idx_agent_workflow_run_retry_root
    ON agent_workflow_run (retry_root_run_id, created_at);

CREATE TABLE agent_workflow_run_node (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    logical_node_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    tool_version INTEGER NOT NULL CHECK (tool_version > 0),
    risk_level VARCHAR(32) NOT NULL CHECK (risk_level IN ('READ_ONLY', 'MUTATING')),
    required BOOLEAN NOT NULL DEFAULT TRUE,
    condition_type VARCHAR(32) NOT NULL,
    dependency_node_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    argument_template JSONB NOT NULL,
    expose_outputs JSONB NOT NULL DEFAULT '[]'::jsonb,
    resolved_input JSONB,
    output_payload JSONB,
    exposed_output JSONB,
    prompt_appendix TEXT,
    source_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED',
        'WAITING_APPROVAL', 'CANCELLED', 'REUSED'
    )),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    tool_call_id UUID REFERENCES tool_call(id) ON DELETE SET NULL,
    plan_node_id UUID REFERENCES agent_plan_node(id) ON DELETE SET NULL,
    reused_from_node_id UUID REFERENCES agent_workflow_run_node(id) ON DELETE SET NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    estimated_cost_cny NUMERIC(18, 6) NOT NULL DEFAULT 0 CHECK (estimated_cost_cny >= 0),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, logical_node_id)
);

CREATE INDEX idx_agent_workflow_run_node_run_status
    ON agent_workflow_run_node (run_id, status, created_at);

CREATE TABLE agent_workflow_node_attempt (
    id UUID PRIMARY KEY,
    workflow_node_id UUID NOT NULL REFERENCES agent_workflow_run_node(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    run_attempt INTEGER NOT NULL CHECK (run_attempt > 0),
    worker_id VARCHAR(160),
    lease_token UUID NOT NULL,
    tool_call_id UUID REFERENCES tool_call(id) ON DELETE SET NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN (
        'RUNNING', 'SUCCEEDED', 'FAILED', 'WAITING_APPROVAL', 'CANCELLED'
    )),
    resolved_input JSONB NOT NULL,
    output_payload JSONB,
    input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    estimated_cost_cny NUMERIC(18, 6) NOT NULL DEFAULT 0 CHECK (estimated_cost_cny >= 0),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    UNIQUE (workflow_node_id, attempt_no)
);

CREATE INDEX idx_agent_workflow_node_attempt_run
    ON agent_workflow_node_attempt (run_id, started_at);
