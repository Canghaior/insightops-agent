CREATE OR REPLACE FUNCTION enforce_public_beta_run_switch()
RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public_registration registration
        JOIN public_beta_control control ON control.singleton_id = 1
        WHERE registration.workspace_id = NEW.workspace_id
          AND control.runs_enabled = FALSE
    ) THEN
        RAISE EXCEPTION 'PUBLIC_BETA_RUNS_DISABLED' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_agent_run_public_beta_switch
    BEFORE INSERT ON agent_run
    FOR EACH ROW EXECUTE FUNCTION enforce_public_beta_run_switch();
