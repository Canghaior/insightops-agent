-- P1 installations created before account administration had only bootstrap owners.
-- Promote those credentialed owners once, without coupling Flyway to a local .env username.
UPDATE app_user user_account
SET system_role = 'SYSTEM_ADMIN', updated_at = now()
WHERE EXISTS (
    SELECT 1
    FROM workspace_member member
    JOIN user_credential credential ON credential.user_id = member.user_id
    WHERE member.user_id = user_account.id AND member.role = 'OWNER'
);

-- Their password was chosen by the local operator rather than issued as a temporary password.
UPDATE user_credential credential
SET must_change_password = FALSE, updated_at = now()
WHERE EXISTS (
    SELECT 1 FROM app_user user_account
    WHERE user_account.id = credential.user_id
      AND user_account.system_role = 'SYSTEM_ADMIN'
);
