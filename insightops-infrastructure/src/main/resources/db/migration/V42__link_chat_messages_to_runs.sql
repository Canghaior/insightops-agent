-- Keep the exact run behind an answer when restoring conversation history.
ALTER TABLE conversation_message ADD COLUMN run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL;
CREATE INDEX ix_conversation_message_run ON conversation_message(run_id) WHERE run_id IS NOT NULL;

-- Historical answers are linked only when BOTH sides have a unique exact match.
-- Ambiguous records remain unlinked; never choose a run by answer text alone.
WITH matches AS (
    SELECT message.id AS message_id, run.id AS run_id,
           count(*) OVER (PARTITION BY message.id) AS message_matches,
           count(*) OVER (PARTITION BY run.id) AS run_matches
    FROM conversation_message message
    JOIN conversation_session session ON session.id = message.session_id
    JOIN agent_run run ON run.session_id = message.session_id
        AND run.workspace_id = session.workspace_id
        AND run.owner_user_id = session.owner_user_id
        AND run.answer = message.content
        AND run.finished_at = message.created_at
        AND run.status = 'SUCCEEDED'
    WHERE message.role = 'ASSISTANT'
)
UPDATE conversation_message message SET run_id = matches.run_id
FROM matches
WHERE message.id = matches.message_id AND matches.message_matches = 1 AND matches.run_matches = 1;
