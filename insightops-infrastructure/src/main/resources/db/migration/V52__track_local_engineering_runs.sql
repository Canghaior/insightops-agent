CREATE TABLE local_engineering_run (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL,
    task_version_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL,
    request_id UUID NOT NULL,
    baseline_id UUID NOT NULL,
    baseline_version INTEGER NOT NULL CHECK (baseline_version > 0),
    pom_sha256 VARCHAR(64) NOT NULL CHECK (pom_sha256 ~ '^[0-9a-f]{64}$'),
    input JSONB NOT NULL CHECK (jsonb_typeof(input) = 'object'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED', 'ANALYZING', 'BASELINE', 'EDITING', 'TESTING',
        'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED', 'INTERRUPTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    note TEXT NOT NULL DEFAULT '',
    verification_id UUID,
    patch_sha256 VARCHAR(64) CHECK (patch_sha256 IS NULL OR patch_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK ((finished_at IS NULL) = (status IN ('QUEUED', 'ANALYZING', 'BASELINE', 'EDITING', 'TESTING'))),
    UNIQUE (report_id, task_version_id, actor_id, request_id),
    FOREIGN KEY (report_id, task_version_id)
        REFERENCES report_engineering_task_version(report_id, id) ON DELETE CASCADE,
    FOREIGN KEY (report_id, verification_id)
        REFERENCES report_verification(report_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX local_engineering_single_active ON local_engineering_run ((1)) WHERE finished_at IS NULL;
CREATE INDEX local_engineering_run_task ON local_engineering_run (report_id, task_version_id, actor_id, created_at DESC);

CREATE TABLE local_engineering_event (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES local_engineering_run(id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    message TEXT NOT NULL,
    details JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX local_engineering_event_run ON local_engineering_event (run_id, sequence);
