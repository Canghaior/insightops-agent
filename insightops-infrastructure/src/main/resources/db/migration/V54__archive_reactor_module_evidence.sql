ALTER TABLE build_evidence_file ADD COLUMN module_path VARCHAR(240)
    CHECK (module_path IS NULL OR (kind = 'SUREFIRE_XML' AND length(module_path) > 0));

ALTER TABLE build_evidence_file DROP CONSTRAINT build_evidence_file_batch_id_file_name_key;
CREATE UNIQUE INDEX idx_build_evidence_file_module_name
    ON build_evidence_file (batch_id, COALESCE(module_path, ''), file_name);

ALTER TABLE build_evidence_file DROP CONSTRAINT build_evidence_file_position_check;
ALTER TABLE build_evidence_file ADD CONSTRAINT build_evidence_file_position_check
    CHECK (position >= 0 AND position < 163);
