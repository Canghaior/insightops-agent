CREATE TABLE agent_plan (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'ACTIVE', 'COMPLETED', 'LIMIT_REACHED', 'FAILED', 'CANCELLED'
    )),
    max_parallelism INTEGER NOT NULL CHECK (max_parallelism > 0),
    created_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    UNIQUE (run_id, version)
);

CREATE INDEX idx_agent_plan_run_created
    ON agent_plan (run_id, created_at DESC);

CREATE TABLE agent_plan_node (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES agent_plan(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    provider_tool_call_id VARCHAR(255) NOT NULL,
    plan_round INTEGER NOT NULL CHECK (plan_round > 0),
    position INTEGER NOT NULL CHECK (position > 0),
    tool_name VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL CHECK (risk_level IN (
        'READ_ONLY', 'MUTATING', 'UNKNOWN'
    )),
    required BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED',
        'WAITING_APPROVAL', 'CANCELLED'
    )),
    input_payload JSONB NOT NULL,
    tool_call_id UUID REFERENCES tool_call(id) ON DELETE SET NULL,
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (plan_id, plan_round, position)
);

CREATE INDEX idx_agent_plan_node_run_round
    ON agent_plan_node (run_id, plan_round, position);

CREATE TABLE agent_plan_dependency (
    node_id UUID NOT NULL REFERENCES agent_plan_node(id) ON DELETE CASCADE,
    depends_on_node_id UUID NOT NULL REFERENCES agent_plan_node(id) ON DELETE CASCADE,
    PRIMARY KEY (node_id, depends_on_node_id),
    CHECK (node_id <> depends_on_node_id)
);

CREATE TABLE agent_run_budget (
    run_id UUID PRIMARY KEY REFERENCES agent_run(id) ON DELETE CASCADE,
    max_nodes INTEGER NOT NULL CHECK (max_nodes > 0),
    max_parallelism INTEGER NOT NULL CHECK (max_parallelism > 0),
    max_tool_attempts INTEGER NOT NULL CHECK (max_tool_attempts > 0),
    max_model_tokens BIGINT NOT NULL CHECK (max_model_tokens > 0),
    max_estimated_cost_cny NUMERIC(18, 6) NOT NULL CHECK (max_estimated_cost_cny > 0),
    used_nodes INTEGER NOT NULL DEFAULT 0 CHECK (used_nodes >= 0),
    used_tool_attempts INTEGER NOT NULL DEFAULT 0 CHECK (used_tool_attempts >= 0),
    used_model_tokens BIGINT NOT NULL DEFAULT 0 CHECK (used_model_tokens >= 0),
    estimated_cost_cny NUMERIC(18, 6) NOT NULL DEFAULT 0 CHECK (estimated_cost_cny >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'CLOSED')),
    exhaustion_reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
