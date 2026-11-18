CREATE TABLE business_system (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    purpose VARCHAR(1000) NOT NULL,
    key_processes VARCHAR(1000) NOT NULL,
    technology_stack VARCHAR(1000) NOT NULL,
    current_problems VARCHAR(1000) NOT NULL,
    constraints_text VARCHAR(1000) NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, owner_user_id, id)
);

CREATE INDEX idx_business_system_owner_updated ON business_system (workspace_id, owner_user_id, updated_at DESC, id);

CREATE TABLE business_system_upload (
    system_id UUID NOT NULL REFERENCES business_system(id) ON DELETE CASCADE,
    upload_id UUID NOT NULL REFERENCES knowledge_upload(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 9),
    PRIMARY KEY (system_id, upload_id),
    UNIQUE (system_id, position)
);
CREATE INDEX idx_business_system_upload_file ON business_system_upload (upload_id);

-- Removing a linked upload changes the editable dossier, including its concurrency token.
CREATE FUNCTION advance_business_system_revision_on_upload_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE business_system SET revision = revision + 1, updated_at = now()
    WHERE id IN (SELECT system_id FROM business_system_upload WHERE upload_id = OLD.id);
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_business_system_upload_delete BEFORE DELETE ON knowledge_upload
    FOR EACH ROW EXECUTE FUNCTION advance_business_system_revision_on_upload_delete();

ALTER TABLE agent_workflow_run
    ADD COLUMN business_system_id UUID,
    ADD CONSTRAINT fk_workflow_run_business_system
        FOREIGN KEY (workspace_id, owner_user_id, business_system_id)
        REFERENCES business_system(workspace_id, owner_user_id, id),
    ADD CONSTRAINT ck_workflow_run_business_system_input
        CHECK (business_system_id IS NULL OR
            (input_snapshot #>> '{systemSnapshot,id}') IS NOT DISTINCT FROM business_system_id::text);

CREATE INDEX idx_workflow_run_business_system ON agent_workflow_run
    (workspace_id, owner_user_id, business_system_id, created_at DESC, run_id)
    WHERE business_system_id IS NOT NULL;
