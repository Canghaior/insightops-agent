CREATE TABLE knowledge_upload (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL UNIQUE REFERENCES knowledge_source(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    uploaded_by UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(128) NOT NULL UNIQUE,
    media_type VARCHAR(128) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size BETWEEN 1 AND 20971520),
    sha256 CHAR(64) NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'
        CHECK (visibility IN ('PRIVATE', 'WORKSPACE')),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'DELETING')),
    page_count INTEGER NOT NULL DEFAULT 0 CHECK (page_count >= 0),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_upload_visible
    ON knowledge_upload (workspace_id, uploaded_by, visibility, created_at DESC);
CREATE INDEX idx_knowledge_upload_status
    ON knowledge_upload (status, updated_at);
