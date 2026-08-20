ALTER TABLE tracked_project
    ADD COLUMN source_types TEXT[] NOT NULL DEFAULT ARRAY[
        'GITHUB_RELEASE', 'GITHUB_ISSUE', 'GITHUB_PULL_REQUEST', 'GITHUB_SECURITY_ADVISORY'
    ]::TEXT[],
    ADD COLUMN sync_lock_token UUID,
    ADD COLUMN sync_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN sync_current_source_type VARCHAR(48),
    ADD COLUMN sync_discovered_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN sync_stored_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE intelligence_event
    ADD COLUMN state VARCHAR(32),
    ADD COLUMN author_login VARCHAR(128),
    ADD COLUMN labels TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN risk_level VARCHAR(16),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_intelligence_event_type_occurred
    ON intelligence_event (event_type, occurred_at DESC);
CREATE INDEX idx_intelligence_event_labels
    ON intelligence_event USING GIN (labels);

CREATE TABLE user_watch_rule (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    project_id UUID REFERENCES tracked_project(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    keywords TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    excluded_keywords TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    event_types TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    minimum_importance SMALLINT NOT NULL DEFAULT 1 CHECK (minimum_importance BETWEEN 1 AND 5),
    immediate_notification BOOLEAN NOT NULL DEFAULT TRUE,
    include_in_digest BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (cardinality(keywords) > 0 OR cardinality(event_types) > 0 OR project_id IS NOT NULL)
);

CREATE INDEX idx_user_watch_rule_active
    ON user_watch_rule (workspace_id, enabled, project_id);

CREATE TABLE event_rule_match (
    rule_id UUID NOT NULL REFERENCES user_watch_rule(id) ON DELETE CASCADE,
    event_id UUID NOT NULL REFERENCES intelligence_event(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    matched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, event_id)
);

CREATE INDEX idx_event_rule_match_user
    ON event_rule_match (user_id, workspace_id, matched_at DESC);

ALTER TABLE user_notification DROP CONSTRAINT user_notification_notification_type_check;
ALTER TABLE user_notification ADD CONSTRAINT user_notification_notification_type_check
    CHECK (notification_type IN (
        'ANALYSIS_READY', 'HIGH_RISK', 'ANALYSIS_FAILED', 'DIGEST_READY', 'RULE_MATCH'
    ));

CREATE TABLE research_answer_feedback (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    helpful BOOLEAN,
    reason VARCHAR(48),
    comment VARCHAR(1000),
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING', 'REVIEWED', 'ADDED_TO_EVAL', 'DISMISSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, run_id)
);

CREATE TABLE research_citation_feedback (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    citation_url VARCHAR(1024) NOT NULL,
    correct BOOLEAN NOT NULL,
    comment VARCHAR(1000),
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING', 'REVIEWED', 'ADDED_TO_EVAL', 'DISMISSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, run_id, citation_url)
);

CREATE INDEX idx_research_answer_feedback_review
    ON research_answer_feedback (review_status, created_at DESC);
CREATE INDEX idx_research_citation_feedback_review
    ON research_citation_feedback (review_status, created_at DESC);
