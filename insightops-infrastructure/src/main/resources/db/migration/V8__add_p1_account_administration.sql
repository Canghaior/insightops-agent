ALTER TABLE app_user
    ADD COLUMN system_role VARCHAR(24) NOT NULL DEFAULT 'USER'
        CHECK (system_role IN ('USER', 'SYSTEM_ADMIN'));

CREATE TABLE account_audit_log (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    target_user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    action VARCHAR(64) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_audit_workspace_created
    ON account_audit_log (workspace_id, created_at DESC);

CREATE INDEX idx_app_user_system_role
    ON app_user (system_role, status);
