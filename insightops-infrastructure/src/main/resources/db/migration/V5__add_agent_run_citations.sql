ALTER TABLE agent_run
    ADD COLUMN citations JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE agent_run AS run
SET citations = COALESCE(
        (
            SELECT message.citations
            FROM conversation_message AS message
            WHERE message.session_id = run.session_id
              AND message.role = 'ASSISTANT'
              AND message.content = run.answer
            ORDER BY message.created_at DESC
            LIMIT 1
        ),
        '[]'::jsonb
    )
WHERE run.status = 'SUCCEEDED';

CREATE INDEX idx_agent_run_workspace_status_created
    ON agent_run (workspace_id, status, created_at DESC);
