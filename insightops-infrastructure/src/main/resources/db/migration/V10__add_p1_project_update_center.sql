ALTER TABLE tracked_project
    ADD COLUMN last_sync_at TIMESTAMPTZ,
    ADD COLUMN last_sync_status VARCHAR(24) NOT NULL DEFAULT 'NEVER'
        CHECK (last_sync_status IN ('NEVER', 'RUNNING', 'SUCCEEDED', 'RETRY_WAIT', 'FAILED')),
    ADD COLUMN last_sync_error TEXT,
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN sync_locked_until TIMESTAMPTZ;

CREATE TABLE user_event_read (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    event_id UUID NOT NULL REFERENCES intelligence_event(id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_id)
);

CREATE INDEX idx_user_event_read_workspace_user
    ON user_event_read (workspace_id, user_id, read_at DESC);

CREATE INDEX idx_tracked_project_sync_lock
    ON tracked_project (next_sync_at, sync_locked_until)
    WHERE enabled = TRUE;

UPDATE tracked_project project
SET next_sync_at = now()
WHERE project.enabled = TRUE
  AND EXISTS (
      SELECT 1 FROM user_project_watch watch
      WHERE watch.project_id = project.id AND watch.enabled = TRUE
  );
