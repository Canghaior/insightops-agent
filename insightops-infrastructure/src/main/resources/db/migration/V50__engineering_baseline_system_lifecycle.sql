-- Match the owning system's lifecycle when the existing account-erasure flow removes a workspace.
ALTER TABLE engineering_baseline
    DROP CONSTRAINT engineering_baseline_workspace_id_owner_user_id_system_id_fkey,
    ADD CONSTRAINT fk_engineering_baseline_business_system
        FOREIGN KEY (workspace_id, owner_user_id, system_id)
        REFERENCES business_system(workspace_id, owner_user_id, id) ON DELETE CASCADE;

