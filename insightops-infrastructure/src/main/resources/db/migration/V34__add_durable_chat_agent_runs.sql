CREATE TABLE agent_run_work (
    run_id UUID PRIMARY KEY REFERENCES agent_run(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES conversation_session(id) ON DELETE CASCADE,
    trace_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN (
        'QUEUED', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'CANCELLED'
    )),
    system_admin BOOLEAN NOT NULL DEFAULT FALSE,
    access_level VARCHAR(32) NOT NULL CHECK (access_level IN (
        'WORKSPACE_MEMBER', 'WORKSPACE_OWNER', 'SYSTEM_ADMIN'
    )),
    user_prompt TEXT NOT NULL,
    contextual_prompt TEXT NOT NULL,
    resume_checkpoint_id UUID REFERENCES agent_plan_checkpoint(id) ON DELETE SET NULL,
    recovery_checkpoint_id UUID REFERENCES agent_plan_checkpoint(id) ON DELETE SET NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    claimed_by VARCHAR(160),
    lease_token UUID,
    heartbeat_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_agent_run_work_claimable
    ON agent_run_work (status, lease_expires_at, created_at)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_agent_run_work_owner
    ON agent_run_work (workspace_id, owner_user_id, created_at DESC);

CREATE TABLE agent_run_event (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_type VARCHAR(48) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, sequence)
);

CREATE INDEX idx_agent_run_event_resume
    ON agent_run_event (run_id, sequence);
