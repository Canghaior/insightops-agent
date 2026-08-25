ALTER TABLE app_user DROP CONSTRAINT app_user_status_check;
ALTER TABLE app_user
    ADD CONSTRAINT app_user_status_check
    CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED'));

ALTER TABLE identity_mail_outbox
    ADD COLUMN template_data_ciphertext TEXT,
    ADD COLUMN delivery_provider VARCHAR(24),
    ADD COLUMN provider_message_id VARCHAR(255);

CREATE TABLE public_beta_control (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    registration_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    runs_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status_message VARCHAR(500),
    updated_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

INSERT INTO public_beta_control
    (singleton_id, registration_enabled, runs_enabled, status_message, created_at, updated_at)
VALUES (1, FALSE, TRUE, 'Public registration is not configured yet', now(), now());

CREATE TABLE public_registration (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL UNIQUE REFERENCES workspace(id) ON DELETE CASCADE,
    registration_slot INTEGER,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'DELETED')),
    verification_expires_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (registration_slot IS NULL OR registration_slot > 0)
);

CREATE UNIQUE INDEX uk_public_registration_slot
    ON public_registration (registration_slot)
    WHERE registration_slot IS NOT NULL;

CREATE INDEX idx_public_registration_status_expiry
    ON public_registration (status, verification_expires_at);

CREATE TABLE legal_consent (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    document_type VARCHAR(32) NOT NULL
        CHECK (document_type IN ('TERMS', 'PRIVACY', 'ACCEPTABLE_USE', 'AGE_CONFIRMATION')),
    document_version VARCHAR(32) NOT NULL,
    ip_hash CHAR(64) NOT NULL,
    user_agent_hash CHAR(64) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, document_type, document_version)
);

CREATE INDEX idx_legal_consent_user_accepted
    ON legal_consent (user_id, accepted_at DESC);

CREATE TABLE personal_data_export (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'DOWNLOADED', 'EXPIRED', 'FAILED')),
    storage_key VARCHAR(512),
    download_token_hash CHAR(64) UNIQUE,
    expires_at TIMESTAMPTZ,
    downloaded_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_personal_data_export_user_created
    ON personal_data_export (user_id, created_at DESC);

ALTER TABLE account_deletion_request
    ADD COLUMN purge_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (purge_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    ADD COLUMN purge_started_at TIMESTAMPTZ,
    ADD COLUMN purged_at TIMESTAMPTZ,
    ADD COLUMN purge_error VARCHAR(1000);
