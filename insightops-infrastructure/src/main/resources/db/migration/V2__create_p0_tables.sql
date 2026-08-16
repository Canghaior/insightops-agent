CREATE TABLE workspace (
    id UUID PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    slug VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tracked_project (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    platform VARCHAR(32) NOT NULL DEFAULT 'github',
    repository_owner VARCHAR(128) NOT NULL,
    repository_name VARCHAR(128) NOT NULL,
    canonical_url VARCHAR(512) NOT NULL,
    priority SMALLINT NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, platform, repository_owner, repository_name)
);

CREATE TABLE source_snapshot (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES tracked_project(id),
    source_type VARCHAR(32) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    version_tag VARCHAR(128),
    source_url VARCHAR(1024) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    raw_content JSONB NOT NULL,
    published_at TIMESTAMPTZ,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, source_type, external_id)
);

CREATE TABLE intelligence_event (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES tracked_project(id),
    snapshot_id UUID NOT NULL UNIQUE REFERENCES source_snapshot(id),
    event_type VARCHAR(48) NOT NULL,
    title VARCHAR(512) NOT NULL,
    summary TEXT NOT NULL,
    importance SMALLINT NOT NULL DEFAULT 3 CHECK (importance BETWEEN 1 AND 5),
    occurred_at TIMESTAMPTZ,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE event_evidence (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES intelligence_event(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL REFERENCES source_snapshot(id),
    source_url VARCHAR(1024) NOT NULL,
    evidence_text TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, snapshot_id, sort_order)
);

CREATE TABLE conversation_session (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    title VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE conversation_message (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES conversation_session(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    content TEXT NOT NULL,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    sequence_no INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, sequence_no)
);

CREATE TABLE agent_run (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    session_id UUID REFERENCES conversation_session(id),
    trace_id VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('CREATED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    question TEXT NOT NULL,
    answer TEXT,
    model_provider VARCHAR(48),
    model_name VARCHAR(128),
    tool_rounds INTEGER NOT NULL DEFAULT 0,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    estimated_cost_cny NUMERIC(12, 6),
    failure_code VARCHAR(64),
    failure_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_step (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    step_no INTEGER NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    input_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_payload JSONB,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, step_no)
);

CREATE TABLE tool_call (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    step_id UUID REFERENCES agent_step(id) ON DELETE SET NULL,
    tool_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('REQUESTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT')),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_payload JSONB,
    error_message TEXT,
    duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE TABLE job_task (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    project_id UUID REFERENCES tracked_project(id),
    job_type VARCHAR(64) NOT NULL,
    business_key VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'RETRY_WAIT', 'FAILED', 'DEAD_LETTER')),
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    scheduled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(128),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_type, business_key)
);

CREATE INDEX idx_tracked_project_next_sync
    ON tracked_project (next_sync_at) WHERE enabled = TRUE;
CREATE INDEX idx_source_snapshot_project_published
    ON source_snapshot (project_id, published_at DESC);
CREATE INDEX idx_intelligence_event_project_occurred
    ON intelligence_event (project_id, occurred_at DESC);
CREATE INDEX idx_agent_run_workspace_created
    ON agent_run (workspace_id, created_at DESC);
CREATE INDEX idx_tool_call_run
    ON tool_call (run_id, created_at);
CREATE INDEX idx_job_task_poll
    ON job_task (status, scheduled_at) WHERE status IN ('PENDING', 'RETRY_WAIT');
