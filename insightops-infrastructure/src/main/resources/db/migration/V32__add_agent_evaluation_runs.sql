ALTER TABLE agent_run
    ADD COLUMN run_kind VARCHAR(24) NOT NULL DEFAULT 'CHAT'
        CHECK (run_kind IN ('CHAT', 'EVALUATION'));

CREATE INDEX idx_agent_run_owner_kind_created
    ON agent_run (workspace_id, owner_user_id, run_kind, created_at DESC);

CREATE TABLE agent_evaluation_run (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    dataset_id UUID NOT NULL REFERENCES agent_evaluation_dataset(id),
    candidate_id UUID NOT NULL REFERENCES agent_release_candidate(id),
    baseline_run_id UUID REFERENCES agent_evaluation_run(id) ON DELETE SET NULL,
    requested_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED')),
    case_count INTEGER NOT NULL CHECK (case_count > 0),
    passed_case_count INTEGER NOT NULL DEFAULT 0 CHECK (passed_case_count >= 0),
    success_rate NUMERIC(8, 6),
    tool_accuracy NUMERIC(8, 6),
    recovery_rate NUMERIC(8, 6),
    citation_rate NUMERIC(8, 6),
    average_duration_ms BIGINT,
    average_tokens BIGINT,
    average_cost_cny NUMERIC(18, 6),
    failure_code VARCHAR(64),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_agent_evaluation_run_workspace_created
    ON agent_evaluation_run (workspace_id, created_at DESC);
CREATE INDEX idx_agent_evaluation_run_candidate_created
    ON agent_evaluation_run (candidate_id, created_at DESC);

CREATE TABLE agent_evaluation_case_result (
    id UUID PRIMARY KEY,
    evaluation_run_id UUID NOT NULL REFERENCES agent_evaluation_run(id) ON DELETE CASCADE,
    case_id UUID NOT NULL REFERENCES agent_evaluation_case(id),
    agent_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PASSED', 'FAILED', 'ERROR')),
    actual_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
    missing_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
    forbidden_tools_used JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    tool_selection_correct BOOLEAN NOT NULL,
    plan_completed BOOLEAN NOT NULL,
    recovery_observed BOOLEAN NOT NULL,
    citation_requirements_met BOOLEAN NOT NULL,
    duration_ms BIGINT NOT NULL CHECK (duration_ms >= 0),
    total_tokens BIGINT NOT NULL CHECK (total_tokens >= 0),
    estimated_cost_cny NUMERIC(18, 6) NOT NULL CHECK (estimated_cost_cny >= 0),
    failure_code VARCHAR(64),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (evaluation_run_id, case_id)
);

CREATE INDEX idx_agent_evaluation_case_result_run
    ON agent_evaluation_case_result (evaluation_run_id, status, created_at);
