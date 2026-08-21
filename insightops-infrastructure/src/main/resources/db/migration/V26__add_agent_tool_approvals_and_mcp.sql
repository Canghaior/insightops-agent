ALTER TABLE tool_call
    DROP CONSTRAINT IF EXISTS tool_call_status_check;

ALTER TABLE tool_call
    ADD CONSTRAINT tool_call_status_check
        CHECK (status IN (
            'REQUESTED', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'REJECTED',
            'COMPENSATED', 'FAILED', 'TIMED_OUT', 'CANCELLED'
        ));

CREATE TABLE agent_tool_approval (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    step_id UUID NOT NULL REFERENCES agent_step(id) ON DELETE CASCADE,
    tool_call_id UUID NOT NULL UNIQUE REFERENCES tool_call(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    tool_name VARCHAR(64) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    request_payload JSONB NOT NULL,
    result_payload JSONB,
    idempotency_key VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'PENDING', 'EXECUTED', 'REJECTED', 'EXPIRED', 'FAILED', 'COMPENSATED'
    )),
    error_code VARCHAR(64),
    decision_comment VARCHAR(500),
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    compensated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, idempotency_key)
);

CREATE INDEX idx_agent_tool_approval_actor_status
    ON agent_tool_approval (workspace_id, user_id, status, created_at DESC);

CREATE TABLE agent_tool_effect (
    id UUID PRIMARY KEY,
    approval_id UUID NOT NULL UNIQUE REFERENCES agent_tool_approval(id) ON DELETE CASCADE,
    effect_key VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL CHECK (status IN ('APPLIED', 'COMPENSATED')),
    before_payload JSONB NOT NULL,
    after_payload JSONB NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    compensated_at TIMESTAMPTZ
);

CREATE TABLE mcp_connection (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    endpoint VARCHAR(1000) NOT NULL,
    allowed_tools JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);

CREATE INDEX idx_mcp_connection_workspace_enabled
    ON mcp_connection (workspace_id, enabled, name);
