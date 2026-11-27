CREATE TABLE report_claim_review (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES research_report(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    verification_id UUID,
    draft JSONB NOT NULL,
    evidence JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (report_id, author_id, request_id),
    FOREIGN KEY (report_id, verification_id) REFERENCES report_verification(report_id, id) ON DELETE CASCADE
);
CREATE INDEX report_claim_review_history ON report_claim_review(report_id, created_at, id);
