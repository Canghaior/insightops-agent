CREATE TABLE build_evidence_batch (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    system_id UUID NOT NULL,
    report_id UUID NOT NULL REFERENCES research_report(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    role VARCHAR(12) NOT NULL CHECK (role IN ('BASELINE', 'CANDIDATE')),
    imported_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL,
    counts JSONB NOT NULL,
    analysis JSONB NOT NULL,
    UNIQUE (id, system_id, workspace_id, owner_user_id),
    UNIQUE (workspace_id, owner_user_id, system_id, request_id),
    FOREIGN KEY (workspace_id, owner_user_id, system_id)
        REFERENCES business_system(workspace_id, owner_user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_build_evidence_owner_history
    ON build_evidence_batch(workspace_id, owner_user_id, system_id, imported_at DESC, id);

CREATE TABLE build_evidence_file (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES build_evidence_batch(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0 AND position < 43),
    file_name VARCHAR(240) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('SUREFIRE_XML','MAVEN_LOG','COMMAND_RECORD','EXCLUSION_MANIFEST')),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    byte_size INTEGER NOT NULL CHECK (byte_size BETWEEN 1 AND 2097152),
    content BYTEA NOT NULL CHECK (octet_length(content) = byte_size),
    UNIQUE (batch_id, position),
    UNIQUE (batch_id, file_name)
);
