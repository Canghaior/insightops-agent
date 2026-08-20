ALTER TABLE knowledge_source
    DROP CONSTRAINT knowledge_source_source_type_check;

ALTER TABLE knowledge_source
    ADD CONSTRAINT knowledge_source_source_type_check
    CHECK (source_type IN (
        'OFFICIAL_DOCUMENTATION',
        'MIGRATION_GUIDE',
        'OFFICIAL_RELEASE_NOTES',
        'OFFICIAL_BLOG_RSS',
        'OFFICIAL_ROADMAP',
        'USER_UPLOAD'
    ));

ALTER TABLE knowledge_source
    ADD COLUMN fetch_etag VARCHAR(512),
    ADD COLUMN fetch_last_modified VARCHAR(255);

CREATE INDEX idx_knowledge_source_type
    ON knowledge_source (workspace_id, source_type, enabled);
