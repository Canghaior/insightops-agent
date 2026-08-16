package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAdminAccountStore implements AdminAccountStore {

    private final JdbcClient jdbcClient;

    public JdbcAdminAccountStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<ManagedUser> listUsers(UUID workspaceId) {
        return jdbcClient.sql(userSelect() + """
                where member.workspace_id = :workspaceId
                order by case user_account.system_role when 'SYSTEM_ADMIN' then 0 else 1 end,
                         case member.role when 'OWNER' then 0 else 1 end,
                         lower(user_account.username)
                """)
                .param("workspaceId", workspaceId)
                .query((resultSet, rowNum) -> user(resultSet))
                .list();
    }

    @Override
    public Optional<ManagedUser> findUser(UUID workspaceId, UUID userId) {
        return jdbcClient.sql(userSelect() + """
                where member.workspace_id = :workspaceId and user_account.id = :userId
                """)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .query((resultSet, rowNum) -> user(resultSet))
                .optional();
    }

    @Override
    @Transactional
    public ManagedUser createUser(
            UUID userId,
            UUID workspaceId,
            String username,
            String displayName,
            String passwordHash,
            String systemRole,
            String workspaceRole,
            Instant now) {
        OffsetDateTime timestamp = timestamp(now);
        jdbcClient.sql("""
                insert into app_user
                    (id, username, display_name, status, system_role, created_at, updated_at)
                values (:id, :username, :displayName, 'ACTIVE', :systemRole, :now, :now)
                """)
                .param("id", userId)
                .param("username", username)
                .param("displayName", displayName)
                .param("systemRole", systemRole)
                .param("now", timestamp)
                .update();
        jdbcClient.sql("""
                insert into user_credential
                    (user_id, password_hash, must_change_password, updated_at)
                values (:userId, :passwordHash, true, :now)
                """)
                .param("userId", userId)
                .param("passwordHash", passwordHash)
                .param("now", timestamp)
                .update();
        jdbcClient.sql("""
                insert into workspace_member (workspace_id, user_id, role, created_at)
                values (:workspaceId, :userId, :role, :now)
                """)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .param("role", workspaceRole)
                .param("now", timestamp)
                .update();
        return findUser(workspaceId, userId).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<ManagedUser> updateStatus(
            UUID workspaceId, UUID userId, String status, Instant now) {
        int updated = jdbcClient.sql("""
                update app_user user_account
                set status = :status, updated_at = :now
                where user_account.id = :userId
                  and exists (
                    select 1 from workspace_member member
                    where member.user_id = user_account.id and member.workspace_id = :workspaceId)
                """)
                .param("status", status)
                .param("now", timestamp(now))
                .param("userId", userId)
                .param("workspaceId", workspaceId)
                .update();
        if (updated == 0) return Optional.empty();
        if ("DISABLED".equals(status)) revokeSessions(userId, now);
        return findUser(workspaceId, userId);
    }

    @Override
    public Optional<ManagedUser> updateWorkspaceRole(
            UUID workspaceId, UUID userId, String workspaceRole, Instant now) {
        int updated = jdbcClient.sql("""
                update workspace_member set role = :role
                where workspace_id = :workspaceId and user_id = :userId
                """)
                .param("role", workspaceRole)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .update();
        if (updated == 0) return Optional.empty();
        jdbcClient.sql("update app_user set updated_at = :now where id = :userId")
                .param("now", timestamp(now)).param("userId", userId).update();
        return findUser(workspaceId, userId);
    }

    @Override
    @Transactional
    public boolean resetPassword(UUID workspaceId, UUID userId, String passwordHash, Instant now) {
        int updated = jdbcClient.sql("""
                update user_credential credential
                set password_hash = :passwordHash,
                    must_change_password = true,
                    password_changed_at = null,
                    updated_at = :now
                where credential.user_id = :userId
                  and exists (
                    select 1 from workspace_member member
                    where member.user_id = credential.user_id and member.workspace_id = :workspaceId)
                """)
                .param("passwordHash", passwordHash)
                .param("now", timestamp(now))
                .param("userId", userId)
                .param("workspaceId", workspaceId)
                .update();
        if (updated == 1) revokeSessions(userId, now);
        return updated == 1;
    }

    @Override
    public void appendAudit(
            UUID auditId,
            UUID workspaceId,
            UUID actorUserId,
            UUID targetUserId,
            String action,
            String detailsJson,
            Instant now) {
        jdbcClient.sql("""
                insert into account_audit_log
                    (id, workspace_id, actor_user_id, target_user_id, action, details, created_at)
                values (:id, :workspaceId, :actorUserId, :targetUserId,
                        :action, cast(:details as jsonb), :now)
                """)
                .param("id", auditId)
                .param("workspaceId", workspaceId)
                .param("actorUserId", actorUserId)
                .param("targetUserId", targetUserId)
                .param("action", action)
                .param("details", detailsJson == null ? "{}" : detailsJson)
                .param("now", timestamp(now))
                .update();
    }

    @Override
    public List<AccountAudit> listAudit(UUID workspaceId, int limit) {
        return jdbcClient.sql("""
                select audit.id, audit.actor_user_id, actor.username as actor_username,
                       audit.target_user_id, target.username as target_username,
                       audit.action, audit.details::text as details, audit.created_at
                from account_audit_log audit
                left join app_user actor on actor.id = audit.actor_user_id
                left join app_user target on target.id = audit.target_user_id
                where audit.workspace_id = :workspaceId
                order by audit.created_at desc
                limit :limit
                """)
                .param("workspaceId", workspaceId)
                .param("limit", limit)
                .query((resultSet, rowNum) -> new AccountAudit(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("actor_user_id", UUID.class),
                        resultSet.getString("actor_username"),
                        resultSet.getObject("target_user_id", UUID.class),
                        resultSet.getString("target_username"),
                        resultSet.getString("action"),
                        resultSet.getString("details"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private void revokeSessions(UUID userId, Instant now) {
        jdbcClient.sql("""
                update auth_session set revoked_at = :now
                where user_id = :userId and revoked_at is null
                """)
                .param("now", timestamp(now))
                .param("userId", userId)
                .update();
    }

    private static String userSelect() {
        return """
                select user_account.id as user_id, user_account.username,
                       user_account.display_name, user_account.status,
                       user_account.system_role, member.role as workspace_role,
                       credential.must_change_password,
                       user_account.created_at, user_account.updated_at
                from app_user user_account
                join workspace_member member on member.user_id = user_account.id
                join user_credential credential on credential.user_id = user_account.id
                """;
    }

    private static ManagedUser user(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ManagedUser(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("status"),
                resultSet.getString("system_role"),
                resultSet.getString("workspace_role"),
                resultSet.getBoolean("must_change_password"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
