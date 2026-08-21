ALTER TABLE agent_plan DROP CONSTRAINT IF EXISTS agent_plan_status_check;
ALTER TABLE agent_plan ADD CONSTRAINT agent_plan_status_check CHECK (status IN (
    'ACTIVE', 'PAUSE_REQUESTED', 'PAUSED', 'COMPLETED', 'LIMIT_REACHED',
    'FAILED', 'CANCELLED', 'SUPERSEDED'
));

ALTER TABLE agent_plan
    ADD COLUMN pause_requested_at TIMESTAMPTZ,
    ADD COLUMN paused_at TIMESTAMPTZ,
    ADD COLUMN resumed_at TIMESTAMPTZ,
    ADD COLUMN execution_epoch INTEGER NOT NULL DEFAULT 1 CHECK (execution_epoch > 0);

ALTER TABLE agent_plan_node DROP CONSTRAINT IF EXISTS agent_plan_node_status_check;
ALTER TABLE agent_plan_node ADD CONSTRAINT agent_plan_node_status_check CHECK (status IN (
    'PENDING', 'BLOCKED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED',
    'WAITING_APPROVAL', 'CANCELLED'
));

ALTER TABLE agent_plan_node
    ADD COLUMN condition_type VARCHAR(32) NOT NULL DEFAULT 'ALL_TERMINAL' CHECK (condition_type IN (
        'ALL_SUCCESS', 'ANY_SUCCESS', 'ANY_FAILED', 'ERROR_CODE_MATCH', 'ALL_TERMINAL', 'ALWAYS'
    )),
    ADD COLUMN expected_error_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0);

CREATE TABLE agent_plan_revision (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES agent_plan(id) ON DELETE CASCADE,
    version INTEGER NOT NULL CHECK (version > 0),
    reason VARCHAR(64) NOT NULL,
    graph_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (plan_id, version)
);

CREATE TABLE agent_plan_checkpoint (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES agent_plan(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    reason VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('AVAILABLE', 'CONSUMED', 'INVALID')),
    state_json JSONB NOT NULL,
    budget_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    resumed_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    UNIQUE (plan_id, sequence)
);

CREATE INDEX idx_agent_plan_checkpoint_owner
    ON agent_plan_checkpoint (workspace_id, user_id, created_at DESC);

CREATE INDEX idx_agent_plan_checkpoint_run
    ON agent_plan_checkpoint (run_id, created_at DESC);

ALTER TABLE agent_plan
    ADD COLUMN resumed_from_checkpoint_id UUID REFERENCES agent_plan_checkpoint(id) ON DELETE SET NULL;

ALTER TABLE agent_run DROP CONSTRAINT IF EXISTS agent_run_status_check;
ALTER TABLE agent_run ADD CONSTRAINT agent_run_status_check CHECK (status IN (
    'CREATED', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'CANCELLED'
));
