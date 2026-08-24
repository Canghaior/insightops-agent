ALTER TABLE app_user
    ADD COLUMN email VARCHAR(320),
    ADD COLUMN email_normalized VARCHAR(320),
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    ADD COLUMN deletion_requested_at TIMESTAMPTZ,
    ADD COLUMN deletion_scheduled_at TIMESTAMPTZ,
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uk_app_user_email_normalized
    ON app_user (email_normalized)
    WHERE email_normalized IS NOT NULL;

ALTER TABLE workspace
    ADD COLUMN description VARCHAR(500),
    ADD COLUMN created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    ADD COLUMN archived_at TIMESTAMPTZ;

UPDATE workspace
SET created_by = '00000000-0000-0000-0000-000000000101'
WHERE id = '00000000-0000-0000-0000-000000000001'
  AND EXISTS (SELECT 1 FROM app_user WHERE id = '00000000-0000-0000-0000-000000000101');

ALTER TABLE auth_session
    ADD COLUMN active_workspace_id UUID REFERENCES workspace(id),
    ADD COLUMN user_agent VARCHAR(500),
    ADD COLUMN ip_hash CHAR(64);

UPDATE auth_session session
SET active_workspace_id = (
    SELECT member.workspace_id
    FROM workspace_member member
    WHERE member.user_id = session.user_id
    ORDER BY CASE member.role WHEN 'OWNER' THEN 0 ELSE 1 END, member.created_at
    LIMIT 1
)
WHERE session.active_workspace_id IS NULL;

CREATE INDEX idx_auth_session_user_active
    ON auth_session (user_id, revoked_at, expires_at DESC);

CREATE TABLE identity_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_type VARCHAR(32) NOT NULL
        CHECK (token_type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at)
);

CREATE INDEX idx_identity_token_user_type
    ON identity_token (user_id, token_type, created_at DESC);

CREATE TABLE user_mfa (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    secret_ciphertext TEXT NOT NULL,
    enabled_at TIMESTAMPTZ,
    last_used_step BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE mfa_recovery_code (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL UNIQUE,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_mfa_recovery_user_unused
    ON mfa_recovery_code (user_id, created_at)
    WHERE used_at IS NULL;

CREATE TABLE workspace_invitation (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    email VARCHAR(320) NOT NULL,
    email_normalized VARCHAR(320) NOT NULL,
    role VARCHAR(24) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    token_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    invited_by UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    accepted_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX uk_workspace_pending_invitation
    ON workspace_invitation (workspace_id, email_normalized)
    WHERE status = 'PENDING';

CREATE INDEX idx_workspace_invitation_workspace_created
    ON workspace_invitation (workspace_id, created_at DESC);

CREATE TABLE identity_mail_outbox (
    id UUID PRIMARY KEY,
    recipient_email VARCHAR(320) NOT NULL,
    template_type VARCHAR(32) NOT NULL
        CHECK (template_type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'WORKSPACE_INVITATION')),
    subject VARCHAR(255) NOT NULL,
    body_ciphertext TEXT NOT NULL,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'RETRY_WAIT', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    scheduled_at TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(128),
    sent_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_identity_mail_outbox_poll
    ON identity_mail_outbox (status, scheduled_at)
    WHERE status IN ('PENDING', 'RETRY_WAIT', 'SENDING');

CREATE TABLE auth_rate_limit (
    scope VARCHAR(48) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    failures INTEGER NOT NULL DEFAULT 0 CHECK (failures >= 0),
    window_started_at TIMESTAMPTZ NOT NULL,
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope, key_hash)
);

CREATE INDEX idx_auth_rate_limit_updated
    ON auth_rate_limit (updated_at);

CREATE TABLE account_deletion_request (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    requested_at TIMESTAMPTZ NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CHECK (scheduled_at > requested_at)
);
