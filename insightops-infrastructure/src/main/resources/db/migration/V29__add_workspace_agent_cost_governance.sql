CREATE TABLE agent_cost_policy (
    workspace_id UUID PRIMARY KEY REFERENCES workspace(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    daily_token_limit BIGINT NOT NULL CHECK (daily_token_limit > 0),
    daily_cost_limit_cny NUMERIC(18, 6) NOT NULL CHECK (daily_cost_limit_cny > 0),
    monthly_token_limit BIGINT NOT NULL CHECK (monthly_token_limit > 0),
    monthly_cost_limit_cny NUMERIC(18, 6) NOT NULL CHECK (monthly_cost_limit_cny > 0),
    max_concurrent_runs INTEGER NOT NULL CHECK (max_concurrent_runs > 0),
    warning_percent INTEGER NOT NULL CHECK (warning_percent BETWEEN 1 AND 99),
    hard_limit_enabled BOOLEAN NOT NULL DEFAULT true,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE agent_cost_reservation (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES agent_run(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    usage_day DATE NOT NULL,
    usage_month DATE NOT NULL CHECK (date_trunc('month', usage_month)::date = usage_month),
    reserved_tokens BIGINT NOT NULL CHECK (reserved_tokens >= 0),
    reserved_cost_cny NUMERIC(18, 6) NOT NULL CHECK (reserved_cost_cny >= 0),
    actual_tokens BIGINT NOT NULL DEFAULT 0 CHECK (actual_tokens >= 0),
    actual_cost_cny NUMERIC(18, 6) NOT NULL DEFAULT 0 CHECK (actual_cost_cny >= 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('RESERVED', 'SETTLED', 'RELEASED', 'REJECTED')),
    reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ
);

CREATE INDEX idx_agent_cost_reservation_workspace_status
    ON agent_cost_reservation (workspace_id, status, created_at DESC);

CREATE TABLE agent_cost_ledger (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    entry_type VARCHAR(24) NOT NULL CHECK (entry_type IN ('RESERVE', 'SETTLE', 'RELEASE', 'REJECT')),
    token_delta BIGINT NOT NULL,
    cost_delta_cny NUMERIC(18, 6) NOT NULL,
    reason VARCHAR(64),
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_agent_cost_ledger_workspace_created
    ON agent_cost_ledger (workspace_id, created_at DESC);

CREATE TABLE agent_cost_usage_daily (
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    usage_day DATE NOT NULL,
    used_tokens BIGINT NOT NULL DEFAULT 0 CHECK (used_tokens >= 0),
    used_cost_cny NUMERIC(18, 6) NOT NULL DEFAULT 0 CHECK (used_cost_cny >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, user_id, usage_day)
);

CREATE TABLE agent_cost_usage_monthly (
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    usage_month DATE NOT NULL CHECK (date_trunc('month', usage_month)::date = usage_month),
    used_tokens BIGINT NOT NULL DEFAULT 0 CHECK (used_tokens >= 0),
    used_cost_cny NUMERIC(18, 6) NOT NULL DEFAULT 0 CHECK (used_cost_cny >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, user_id, usage_month)
);
