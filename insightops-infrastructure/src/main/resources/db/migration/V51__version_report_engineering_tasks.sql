CREATE TABLE report_engineering_task_version (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES research_report(id) ON DELETE CASCADE,
    task_id UUID NOT NULL,
    previous_version_id UUID,
    version INTEGER NOT NULL CHECK (version > 0),
    request_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    author_id UUID NOT NULL,
    author_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    source_run_id UUID NOT NULL,
    system_id UUID NOT NULL,
    system_revision INTEGER NOT NULL CHECK (system_revision > 0),
    engineering_baseline_id UUID,
    engineering_baseline_version INTEGER CHECK (engineering_baseline_version > 0),
    excerpt_line INTEGER NOT NULL CHECK (excerpt_line > 0),
    fields JSONB NOT NULL CHECK (jsonb_typeof(fields) = 'object'),
    CHECK ((engineering_baseline_id IS NULL) = (engineering_baseline_version IS NULL)),
    CHECK ((previous_version_id IS NULL AND version = 1 AND id = task_id)
        OR (previous_version_id IS NOT NULL AND version > 1 AND id <> task_id)),
    UNIQUE (report_id, id),
    UNIQUE (report_id, request_id),
    UNIQUE (task_id, version),
    UNIQUE (report_id, previous_version_id),
    UNIQUE (report_id, task_id, id),
    FOREIGN KEY (report_id, task_id, previous_version_id)
        REFERENCES report_engineering_task_version(report_id, task_id, id),
    FOREIGN KEY (workspace_id, author_id, system_id)
        REFERENCES business_system(workspace_id, owner_user_id, id) ON DELETE CASCADE
);

CREATE TABLE report_engineering_task_basis (
    report_id UUID NOT NULL,
    task_version_id UUID NOT NULL,
    verification_version_id UUID NOT NULL,
    PRIMARY KEY (task_version_id, verification_version_id),
    FOREIGN KEY (report_id, task_version_id)
        REFERENCES report_engineering_task_version(report_id, id) ON DELETE CASCADE,
    FOREIGN KEY (report_id, verification_version_id)
        REFERENCES report_verification(report_id, id) ON DELETE CASCADE
);

CREATE TABLE report_engineering_task_result (
    report_id UUID NOT NULL,
    task_version_id UUID NOT NULL,
    verification_version_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    recorder_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    recorder_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    executed_at TIMESTAMPTZ,
    source_revision VARCHAR(1000) NOT NULL,
    candidate_revision VARCHAR(1000) NOT NULL,
    unfinished VARCHAR(2000) NOT NULL,
    patch_name VARCHAR(180),
    patch_size INTEGER,
    patch_sha256 VARCHAR(64),
    patch_bytes BYTEA,
    CHECK ((patch_bytes IS NULL AND patch_name IS NULL AND patch_size IS NULL AND patch_sha256 IS NULL)
        OR (patch_bytes IS NOT NULL AND patch_name IS NOT NULL AND patch_size IS NOT NULL
            AND patch_sha256 IS NOT NULL AND patch_sha256 ~ '^[0-9a-f]{64}$'
            AND patch_size BETWEEN 1 AND 1048576 AND octet_length(patch_bytes) = patch_size)),
    UNIQUE (report_id, request_id),
    FOREIGN KEY (report_id, task_version_id)
        REFERENCES report_engineering_task_version(report_id, id) ON DELETE CASCADE,
    FOREIGN KEY (report_id, verification_version_id)
        REFERENCES report_verification(report_id, id) ON DELETE CASCADE
);

CREATE INDEX report_engineering_task_result_task_idx ON report_engineering_task_result(task_version_id);
