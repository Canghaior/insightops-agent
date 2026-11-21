CREATE TABLE engineering_baseline (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    system_id UUID NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    label VARCHAR(160) NOT NULL,
    request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    parsed_content JSONB NOT NULL,
    original_bytes BYTEA NOT NULL CHECK (octet_length(original_bytes) BETWEEN 1 AND 6356992),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, owner_user_id, system_id)
        REFERENCES business_system(workspace_id, owner_user_id, id),
    UNIQUE (system_id, version),
    UNIQUE (system_id, request_id)
);

