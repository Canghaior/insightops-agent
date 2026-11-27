ALTER TABLE build_evidence_file
    DROP CONSTRAINT build_evidence_file_kind_check,
    ADD CONSTRAINT build_evidence_file_kind_check
        CHECK (kind IN ('SUREFIRE_XML','MAVEN_LOG','COMMAND_RECORD','EXCLUSION_MANIFEST','JUNIT_XML','GRADLE_LOG'));
