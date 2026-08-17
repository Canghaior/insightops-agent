CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE knowledge_embedding (
    chunk_id UUID NOT NULL REFERENCES knowledge_chunk(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions = 1024),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'RETRY_WAIT', 'FAILED')),
    embedding public.vector(1024),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chunk_id, embedding_model),
    CHECK ((status = 'SUCCEEDED' AND embedding IS NOT NULL)
        OR (status <> 'SUCCEEDED' AND embedding IS NULL))
);

CREATE INDEX idx_knowledge_embedding_due
    ON knowledge_embedding (embedding_model, next_attempt_at, locked_until)
    WHERE status IN ('PENDING', 'RETRY_WAIT', 'RUNNING');

CREATE INDEX idx_knowledge_embedding_hnsw
    ON knowledge_embedding USING hnsw (embedding public.vector_cosine_ops)
    WHERE embedding IS NOT NULL;

CREATE INDEX idx_retrieval_trace_workspace_created
    ON retrieval_trace (workspace_id, created_at DESC);
