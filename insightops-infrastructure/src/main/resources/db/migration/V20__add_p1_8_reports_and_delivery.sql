CREATE TABLE research_report (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    report_type VARCHAR(24) NOT NULL DEFAULT 'CUSTOM'
        CHECK (report_type IN ('CUSTOM', 'DAILY', 'WEEKLY')),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    project_ids UUID[] NOT NULL DEFAULT ARRAY[]::UUID[],
    event_types TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    item_count INTEGER NOT NULL,
    high_risk_count INTEGER NOT NULL,
    snapshot JSONB NOT NULL,
    markdown_content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (period_end > period_start),
    CHECK (item_count > 0),
    CHECK (high_risk_count >= 0 AND high_risk_count <= item_count)
);

CREATE INDEX idx_research_report_user_created
    ON research_report (user_id, workspace_id, created_at DESC);

CREATE TABLE report_delivery_channel (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    channel_type VARCHAR(24) NOT NULL DEFAULT 'WEBHOOK'
        CHECK (channel_type IN ('WEBHOOK')),
    endpoint_ciphertext TEXT NOT NULL,
    endpoint_masked VARCHAR(300) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_report_delivery_channel_active_name
    ON report_delivery_channel (user_id, workspace_id, lower(name))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_report_delivery_channel_user
    ON report_delivery_channel (user_id, workspace_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE report_delivery_job (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    report_id UUID NOT NULL REFERENCES research_report(id) ON DELETE CASCADE,
    channel_id UUID NOT NULL REFERENCES report_delivery_channel(id),
    channel_name VARCHAR(100) NOT NULL,
    channel_type VARCHAR(24) NOT NULL CHECK (channel_type IN ('WEBHOOK')),
    endpoint_ciphertext TEXT NOT NULL,
    endpoint_masked VARCHAR(300) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    locked_until TIMESTAMPTZ,
    response_code INTEGER,
    duration_ms BIGINT,
    error_code VARCHAR(64),
    last_error VARCHAR(1000),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (report_id, channel_id),
    CHECK (attempts >= 0 AND max_attempts BETWEEN 1 AND 10)
);

CREATE INDEX idx_report_delivery_job_due
    ON report_delivery_job (status, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY_WAIT', 'RUNNING');

CREATE INDEX idx_report_delivery_job_user_created
    ON report_delivery_job (user_id, workspace_id, created_at DESC);
