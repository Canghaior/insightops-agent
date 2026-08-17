CREATE TABLE rag_evaluation_run (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    dataset_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('RUNNING', 'PASSED', 'FAILED', 'ERROR')),
    case_count INTEGER NOT NULL,
    generation_sample_size INTEGER NOT NULL DEFAULT 0,
    recall_at_k DOUBLE PRECISION,
    mean_reciprocal_rank DOUBLE PRECISION,
    project_hit_rate DOUBLE PRECISION,
    term_coverage DOUBLE PRECISION,
    no_answer_accuracy DOUBLE PRECISION,
    citation_precision DOUBLE PRECISION,
    citation_coverage DOUBLE PRECISION,
    faithfulness DOUBLE PRECISION,
    model_name VARCHAR(128),
    error_message VARCHAR(1000),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_rag_evaluation_run_workspace_started
    ON rag_evaluation_run (workspace_id, started_at DESC);

CREATE TABLE rag_evaluation_case (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_evaluation_run(id) ON DELETE CASCADE,
    case_key VARCHAR(128) NOT NULL,
    question TEXT NOT NULL,
    expected_answerable BOOLEAN NOT NULL,
    expected_project VARCHAR(128),
    predicted_answerable BOOLEAN NOT NULL,
    answerability_correct BOOLEAN NOT NULL,
    project_hit BOOLEAN NOT NULL,
    reciprocal_rank DOUBLE PRECISION NOT NULL,
    term_coverage DOUBLE PRECISION NOT NULL,
    retrieval_mode VARCHAR(24) NOT NULL,
    top_projects JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    citation_precision DOUBLE PRECISION,
    citation_coverage DOUBLE PRECISION,
    faithfulness DOUBLE PRECISION,
    judge_reason VARCHAR(1000),
    generated_answer TEXT,
    UNIQUE (run_id, case_key)
);

CREATE INDEX idx_rag_evaluation_case_run
    ON rag_evaluation_case (run_id, case_key);
