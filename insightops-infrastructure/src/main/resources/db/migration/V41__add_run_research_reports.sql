ALTER TABLE research_report DROP CONSTRAINT research_report_report_type_check;
ALTER TABLE research_report ADD CONSTRAINT research_report_report_type_check
    CHECK (report_type IN ('CUSTOM', 'DAILY', 'WEEKLY', 'RESEARCH'));
ALTER TABLE research_report ADD COLUMN source_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL;
ALTER TABLE research_report ADD COLUMN research_snapshot JSONB;
CREATE UNIQUE INDEX idx_research_report_source_run
    ON research_report (workspace_id, user_id, source_run_id) WHERE source_run_id IS NOT NULL;
