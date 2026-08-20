ALTER TABLE research_answer_feedback
    ADD COLUMN reviewer_user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    ADD COLUMN reviewer_note VARCHAR(1000),
    ADD COLUMN reviewed_at TIMESTAMPTZ;

ALTER TABLE research_citation_feedback
    ADD COLUMN reviewer_user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    ADD COLUMN reviewer_note VARCHAR(1000),
    ADD COLUMN reviewed_at TIMESTAMPTZ;

CREATE TABLE rag_evaluation_candidate (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    source_feedback_type VARCHAR(16) NOT NULL CHECK (source_feedback_type IN ('ANSWER', 'CITATION')),
    source_feedback_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED', 'INCLUDED')),
    question TEXT NOT NULL,
    expected_answerable BOOLEAN NOT NULL,
    expected_project VARCHAR(128),
    category VARCHAR(64) NOT NULL,
    must_hit_terms TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    answer_must_include TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    source_domain VARCHAR(255),
    reviewer_user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    reviewer_note VARCHAR(1000),
    dataset_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_feedback_type, source_feedback_id),
    CHECK ((expected_answerable AND expected_project IS NOT NULL)
        OR (NOT expected_answerable AND expected_project IS NULL))
);

CREATE INDEX idx_rag_evaluation_candidate_workspace_status
    ON rag_evaluation_candidate (workspace_id, status, created_at DESC);

CREATE TABLE rag_dataset_version (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    base_dataset_name VARCHAR(128) NOT NULL,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    gate_run_id UUID REFERENCES rag_evaluation_run(id) ON DELETE SET NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    activated_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,
    UNIQUE (workspace_id, version_number),
    UNIQUE (workspace_id, name)
);

CREATE UNIQUE INDEX uk_rag_dataset_version_active
    ON rag_dataset_version (workspace_id) WHERE status = 'ACTIVE';

ALTER TABLE rag_evaluation_candidate
    ADD CONSTRAINT fk_rag_evaluation_candidate_dataset_version
    FOREIGN KEY (dataset_version_id) REFERENCES rag_dataset_version(id) ON DELETE SET NULL;

CREATE TABLE rag_dataset_version_case (
    version_id UUID NOT NULL REFERENCES rag_dataset_version(id) ON DELETE CASCADE,
    candidate_id UUID NOT NULL REFERENCES rag_evaluation_candidate(id),
    case_key VARCHAR(128) NOT NULL,
    question TEXT NOT NULL,
    expected_answerable BOOLEAN NOT NULL,
    expected_project VARCHAR(128),
    category VARCHAR(64) NOT NULL,
    must_hit_terms TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    answer_must_include TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    source_domain VARCHAR(255),
    PRIMARY KEY (version_id, candidate_id),
    UNIQUE (version_id, case_key)
);

CREATE INDEX idx_rag_dataset_version_case_version
    ON rag_dataset_version_case (version_id, case_key);
