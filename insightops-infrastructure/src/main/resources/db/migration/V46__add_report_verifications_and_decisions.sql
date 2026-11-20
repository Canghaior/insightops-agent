CREATE TABLE report_verification (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES research_report(id) ON DELETE CASCADE,
    source_run_id UUID NOT NULL,
    system_id UUID NOT NULL,
    system_revision INTEGER NOT NULL CHECK (system_revision > 0),
    workspace_id UUID NOT NULL,
    author_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    request_id UUID NOT NULL,
    previous_version_id UUID,
    version INTEGER NOT NULL CHECK (version > 0),
    question VARCHAR(1000) NOT NULL CHECK (btrim(question) <> ''),
    suggestion_ref VARCHAR(2000) NOT NULL CHECK (btrim(suggestion_ref) <> ''),
    origin VARCHAR(20) NOT NULL CHECK (origin IN ('MODEL','HUMAN_OR_TOOL')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('NOT_EXECUTED','EXECUTED','BLOCKED')),
    method VARCHAR(2000) NOT NULL,
    environment VARCHAR(1000) NOT NULL,
    observation VARCHAR(4000) NOT NULL,
    impact VARCHAR(16) NOT NULL CHECK (impact IN ('SUPPORTS','CONTRADICTS','INCONCLUSIVE')),
    limits VARCHAR(2000) NOT NULL,
    blocker VARCHAR(1000) NOT NULL,
    upload_ids UUID[] NOT NULL CHECK (cardinality(upload_ids) <= 10),
    UNIQUE (report_id, id),
    UNIQUE (report_id, request_id),
    UNIQUE (report_id, previous_version_id),
    FOREIGN KEY (workspace_id, author_id, system_id) REFERENCES business_system(workspace_id, owner_user_id, id) ON DELETE CASCADE,
    FOREIGN KEY (report_id, previous_version_id) REFERENCES report_verification(report_id, id),
    CHECK ((previous_version_id IS NULL) = (version = 1)),
    CHECK ((status = 'EXECUTED' AND btrim(method) <> '' AND btrim(environment) <> ''
            AND btrim(observation) <> '' AND btrim(limits) <> '' AND blocker = '')
        OR (status = 'NOT_EXECUTED' AND btrim(method) <> '' AND observation = '' AND blocker = '' AND impact = 'INCONCLUSIVE')
        OR (status = 'BLOCKED' AND btrim(blocker) <> '' AND observation = '' AND impact = 'INCONCLUSIVE'))
);

CREATE TABLE report_human_decision (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES research_report(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    request_id UUID NOT NULL,
    choice VARCHAR(500) NOT NULL CHECK (btrim(choice) <> ''),
    reason VARCHAR(2000) NOT NULL CHECK (btrim(reason) <> ''),
    reconsider_when VARCHAR(2000) NOT NULL CHECK (btrim(reconsider_when) <> ''),
    UNIQUE (report_id, id),
    UNIQUE (report_id, request_id)
);

CREATE TABLE report_decision_basis (
    report_id UUID NOT NULL,
    decision_id UUID NOT NULL,
    verification_version_id UUID NOT NULL,
    PRIMARY KEY (decision_id, verification_version_id),
    FOREIGN KEY (report_id, decision_id) REFERENCES report_human_decision(report_id, id) ON DELETE CASCADE,
    FOREIGN KEY (report_id, verification_version_id) REFERENCES report_verification(report_id, id) ON DELETE CASCADE
);
