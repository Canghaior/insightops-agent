ALTER TABLE knowledge_source
    ADD COLUMN lock_token UUID;

ALTER TABLE knowledge_collection_job
    ADD COLUMN max_page_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN discovered_url_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN visited_url_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN current_url VARCHAR(1024),
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

ALTER TABLE knowledge_collection_job
    ADD CONSTRAINT ck_knowledge_collection_job_progress_nonnegative
    CHECK (max_page_count >= 0
        AND discovered_url_count >= 0
        AND visited_url_count >= 0);

CREATE INDEX idx_knowledge_collection_job_running_heartbeat
    ON knowledge_collection_job (heartbeat_at)
    WHERE status = 'RUNNING';
