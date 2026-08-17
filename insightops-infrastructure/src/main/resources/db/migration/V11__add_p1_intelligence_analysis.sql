ALTER TABLE intelligence_event
    ADD COLUMN analysis_eligible BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE intelligence_analysis (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES tracked_project(id) ON DELETE CASCADE,
    event_id UUID NOT NULL UNIQUE REFERENCES intelligence_event(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'RETRY_WAIT', 'FAILED')),
    automatic BOOLEAN NOT NULL DEFAULT TRUE,
    schema_version INTEGER NOT NULL DEFAULT 1,
    risk_level VARCHAR(16) CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    recommendation VARCHAR(24) CHECK (recommendation IN ('WATCH', 'TRY', 'UPGRADE')),
    evidence_status VARCHAR(24) CHECK (evidence_status IN ('SUFFICIENT', 'INSUFFICIENT')),
    one_line_summary VARCHAR(500),
    major_changes JSONB,
    java_impact TEXT,
    upgrade_value TEXT,
    risks JSONB,
    recommended_actions JSONB,
    evidence_urls JSONB,
    model_provider VARCHAR(64),
    model_name VARCHAR(128),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    estimated_cost_cny NUMERIC(18, 6),
    pricing_effective_date DATE,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(1000),
    requested_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_intelligence_analysis_due
    ON intelligence_analysis (next_attempt_at, locked_until)
    WHERE status IN ('QUEUED', 'RETRY_WAIT');

CREATE INDEX idx_intelligence_analysis_workspace_completed
    ON intelligence_analysis (workspace_id, completed_at DESC)
    WHERE status = 'SUCCEEDED';

CREATE TABLE digest_preference (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    cadence VARCHAR(16) NOT NULL DEFAULT 'OFF'
        CHECK (cadence IN ('OFF', 'DAILY', 'WEEKLY')),
    time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    delivery_hour SMALLINT NOT NULL DEFAULT 9 CHECK (delivery_hour BETWEEN 0 AND 23),
    project_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, workspace_id)
);

CREATE TABLE intelligence_digest (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    cadence VARCHAR(16) NOT NULL CHECK (cadence IN ('DAILY', 'WEEKLY')),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    title VARCHAR(256) NOT NULL,
    summary JSONB NOT NULL,
    item_count INTEGER NOT NULL DEFAULT 0,
    high_risk_count INTEGER NOT NULL DEFAULT 0,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, cadence, period_start, period_end)
);

CREATE INDEX idx_intelligence_digest_user_created
    ON intelligence_digest (user_id, workspace_id, created_at DESC);

CREATE TABLE user_notification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    notification_type VARCHAR(32) NOT NULL
        CHECK (notification_type IN ('ANALYSIS_READY', 'HIGH_RISK', 'ANALYSIS_FAILED', 'DIGEST_READY')),
    entity_id UUID NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'INFO'
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    title VARCHAR(256) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, notification_type, entity_id)
);

CREATE INDEX idx_user_notification_unread
    ON user_notification (user_id, workspace_id, created_at DESC)
    WHERE read_at IS NULL;
