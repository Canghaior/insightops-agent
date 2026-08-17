CREATE TABLE knowledge_source (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES tracked_project(id) ON DELETE CASCADE,
    source_key VARCHAR(128) NOT NULL,
    name VARCHAR(256) NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'OFFICIAL_DOCUMENTATION'
        CHECK (source_type IN ('OFFICIAL_DOCUMENTATION', 'MIGRATION_GUIDE', 'OFFICIAL_RELEASE_NOTES')),
    root_url VARCHAR(1024) NOT NULL,
    discovery_url VARCHAR(1024) NOT NULL,
    allowed_host VARCHAR(255) NOT NULL,
    allowed_path_prefix VARCHAR(512) NOT NULL,
    trust_tier VARCHAR(32) NOT NULL DEFAULT 'T1_PROJECT_DOMAIN',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(24) NOT NULL DEFAULT 'NEVER'
        CHECK (status IN ('NEVER', 'RUNNING', 'SUCCEEDED', 'RETRY_WAIT', 'FAILED')),
    next_sync_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    last_sync_at TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, source_key),
    UNIQUE (workspace_id, root_url)
);

CREATE INDEX idx_knowledge_source_due
    ON knowledge_source (next_sync_at, locked_until)
    WHERE enabled = TRUE AND status <> 'RUNNING';

CREATE TABLE knowledge_document (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
    canonical_url VARCHAR(1024) NOT NULL,
    title VARCHAR(512) NOT NULL,
    language VARCHAR(16) NOT NULL DEFAULT 'en',
    version_label VARCHAR(128),
    etag VARCHAR(512),
    last_modified VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    current_revision_id UUID,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_id, canonical_url)
);

CREATE TABLE knowledge_revision (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    content_sha256 CHAR(64) NOT NULL,
    content_text TEXT NOT NULL,
    character_count INTEGER NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, content_sha256)
);

ALTER TABLE knowledge_document
    ADD CONSTRAINT fk_knowledge_document_current_revision
    FOREIGN KEY (current_revision_id) REFERENCES knowledge_revision(id) ON DELETE SET NULL;

CREATE TABLE knowledge_chunk (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL REFERENCES knowledge_revision(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    heading_path VARCHAR(1000),
    content TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    character_count INTEGER NOT NULL,
    estimated_tokens INTEGER NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (revision_id, chunk_index)
);

CREATE INDEX idx_knowledge_chunk_revision ON knowledge_chunk (revision_id, chunk_index);

CREATE TABLE knowledge_collection_job (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    page_count INTEGER NOT NULL DEFAULT 0,
    new_document_count INTEGER NOT NULL DEFAULT 0,
    changed_document_count INTEGER NOT NULL DEFAULT 0,
    unchanged_document_count INTEGER NOT NULL DEFAULT 0,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_collection_job_source_started
    ON knowledge_collection_job (source_id, started_at DESC);

CREATE TABLE retrieval_trace (
    id UUID PRIMARY KEY,
    run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    query_text TEXT NOT NULL,
    retrieval_mode VARCHAR(24) NOT NULL
        CHECK (retrieval_mode IN ('KEYWORD', 'VECTOR', 'HYBRID')),
    filters JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_summary JSONB NOT NULL DEFAULT '[]'::jsonb,
    result_count INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO knowledge_source (
    id, workspace_id, project_id, source_key, name, root_url, discovery_url,
    allowed_host, allowed_path_prefix, trust_tier, enabled, next_sync_at
)
VALUES
    ('00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000101', 'spring-ai-documentation', 'Spring AI Reference',
     'https://docs.spring.io/spring-ai/reference/', 'https://docs.spring.io/spring-ai/reference/',
     'docs.spring.io', '/spring-ai/reference/', 'T1_PROJECT_DOMAIN', TRUE, '2999-12-31T00:00:00Z'),
    ('00000000-0000-0000-0000-000000000402', '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000102', 'langchain4j-documentation', 'LangChain4j Documentation',
     'https://docs.langchain4j.dev/', 'https://docs.langchain4j.dev/',
     'docs.langchain4j.dev', '/', 'T1_PROJECT_DOMAIN', TRUE, '2999-12-31T00:00:00Z'),
    ('00000000-0000-0000-0000-000000000403', '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000103', 'dify-documentation', 'Dify Documentation',
     'https://docs.dify.ai/en/home', 'https://docs.dify.ai/llms.txt',
     'docs.dify.ai', '/en/', 'T1_PROJECT_DOMAIN', TRUE, '2999-12-31T00:00:00Z')
ON CONFLICT (workspace_id, source_key) DO NOTHING;
