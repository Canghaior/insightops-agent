ALTER TABLE tool_call
    DROP CONSTRAINT IF EXISTS tool_call_status_check;

ALTER TABLE tool_call
    ADD CONSTRAINT tool_call_status_check
        CHECK (status IN (
            'REQUESTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'
        ));

CREATE TABLE tool_call_attempt (
    id UUID PRIMARY KEY,
    tool_call_id UUID NOT NULL REFERENCES tool_call(id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    status VARCHAR(24) NOT NULL
        CHECK (status IN (
            'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'CIRCUIT_OPEN'
        )),
    error_code VARCHAR(64),
    retryable BOOLEAN NOT NULL DEFAULT false,
    retry_delay_ms BIGINT NOT NULL DEFAULT 0 CHECK (retry_delay_ms >= 0),
    duration_ms BIGINT CHECK (duration_ms >= 0),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tool_call_id, attempt_no)
);

CREATE INDEX idx_tool_call_attempt_tool_call
    ON tool_call_attempt (tool_call_id, attempt_no);

CREATE INDEX idx_tool_call_attempt_status_started
    ON tool_call_attempt (status, started_at DESC);
