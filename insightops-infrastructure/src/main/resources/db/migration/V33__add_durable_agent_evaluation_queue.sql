ALTER TABLE agent_evaluation_run
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN claimed_by VARCHAR(160),
    ADD COLUMN lease_token UUID,
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

CREATE INDEX idx_agent_evaluation_run_claimable
    ON agent_evaluation_run (status, lease_expires_at, created_at)
    WHERE status IN ('QUEUED', 'RUNNING');

ALTER TABLE agent_run
    ADD COLUMN evaluation_run_id UUID REFERENCES agent_evaluation_run(id) ON DELETE SET NULL,
    ADD COLUMN evaluation_case_id UUID REFERENCES agent_evaluation_case(id) ON DELETE SET NULL;

CREATE INDEX idx_agent_run_evaluation_case
    ON agent_run (evaluation_run_id, evaluation_case_id, created_at DESC)
    WHERE evaluation_run_id IS NOT NULL;
