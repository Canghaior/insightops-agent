ALTER TABLE tracked_project
    ADD COLUMN sync_interval_hours INTEGER NOT NULL DEFAULT 6
        CHECK (sync_interval_hours BETWEEN 1 AND 720),
    ADD COLUMN chat_aliases TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];

UPDATE tracked_project
SET chat_aliases = CASE repository_name
    WHEN 'spring-ai' THEN ARRAY['spring ai', 'spring-ai']
    WHEN 'langchain4j' THEN ARRAY['langchain4j']
    WHEN 'dify' THEN ARRAY['dify']
    ELSE ARRAY[]::TEXT[]
END;

ALTER TABLE knowledge_source
    ADD COLUMN sync_interval_hours INTEGER NOT NULL DEFAULT 24
        CHECK (sync_interval_hours BETWEEN 1 AND 720);
