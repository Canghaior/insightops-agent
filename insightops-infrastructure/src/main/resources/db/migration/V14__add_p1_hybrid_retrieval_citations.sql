ALTER TABLE agent_run
    ADD COLUMN citation_details JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE conversation_message
    ADD COLUMN citation_details JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_knowledge_chunk_content_fts
    ON knowledge_chunk
    USING gin (to_tsvector('simple', coalesce(content, '')));
