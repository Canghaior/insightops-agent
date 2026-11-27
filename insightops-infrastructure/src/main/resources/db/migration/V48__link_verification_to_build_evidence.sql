ALTER TABLE report_verification
    ADD COLUMN baseline_build_id UUID,
    ADD COLUMN candidate_build_id UUID,
    ADD COLUMN executed_by VARCHAR(200),
    ADD COLUMN build_summary TEXT,
    ADD CHECK (executed_by IS NULL OR (status = 'EXECUTED' AND btrim(executed_by) <> '')),
    ADD CHECK (
        (baseline_build_id IS NULL AND candidate_build_id IS NULL AND build_summary IS NULL)
        OR (baseline_build_id IS NOT NULL AND candidate_build_id IS NOT NULL
            AND baseline_build_id <> candidate_build_id AND build_summary IS NOT NULL AND btrim(build_summary) <> '')
    ),
    ADD FOREIGN KEY (baseline_build_id, system_id, workspace_id, author_id)
        REFERENCES build_evidence_batch (id, system_id, workspace_id, owner_user_id),
    ADD FOREIGN KEY (candidate_build_id, system_id, workspace_id, author_id)
        REFERENCES build_evidence_batch (id, system_id, workspace_id, owner_user_id);
