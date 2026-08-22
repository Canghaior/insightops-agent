CREATE TABLE agent_evaluation_dataset (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(1000),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('LOCKED', 'ARCHIVED')),
    minimum_success_rate NUMERIC(6, 5) NOT NULL CHECK (minimum_success_rate BETWEEN 0 AND 1),
    minimum_tool_accuracy NUMERIC(6, 5) NOT NULL CHECK (minimum_tool_accuracy BETWEEN 0 AND 1),
    minimum_recovery_rate NUMERIC(6, 5) NOT NULL CHECK (minimum_recovery_rate BETWEEN 0 AND 1),
    minimum_citation_rate NUMERIC(6, 5) NOT NULL CHECK (minimum_citation_rate BETWEEN 0 AND 1),
    max_average_duration_ms BIGINT NOT NULL CHECK (max_average_duration_ms > 0),
    max_average_tokens BIGINT NOT NULL CHECK (max_average_tokens > 0),
    max_average_cost_cny NUMERIC(18, 6) NOT NULL CHECK (max_average_cost_cny > 0),
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, name, version)
);

CREATE INDEX idx_agent_evaluation_dataset_workspace_created
    ON agent_evaluation_dataset (workspace_id, created_at DESC);

CREATE TABLE agent_evaluation_case (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES agent_evaluation_dataset(id) ON DELETE CASCADE,
    case_key VARCHAR(96) NOT NULL,
    question TEXT NOT NULL,
    expected_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
    forbidden_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
    required_source_domains JSONB NOT NULL DEFAULT '[]'::jsonb,
    expect_recovery BOOLEAN NOT NULL DEFAULT false,
    max_tool_rounds INTEGER NOT NULL CHECK (max_tool_rounds > 0),
    max_duration_ms BIGINT NOT NULL CHECK (max_duration_ms > 0),
    max_tokens BIGINT NOT NULL CHECK (max_tokens > 0),
    max_cost_cny NUMERIC(18, 6) NOT NULL CHECK (max_cost_cny > 0),
    required BOOLEAN NOT NULL DEFAULT true,
    source_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (dataset_id, case_key)
);

CREATE INDEX idx_agent_evaluation_case_dataset
    ON agent_evaluation_case (dataset_id, case_key);
