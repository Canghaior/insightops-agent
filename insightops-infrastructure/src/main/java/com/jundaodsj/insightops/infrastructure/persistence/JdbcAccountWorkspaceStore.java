package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAccountWorkspaceStore implements AccountWorkspaceStore {

    private final JdbcClient jdbcClient;

    public JdbcAccountWorkspaceStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<AccountRecord> findForLogin(String username) {
        return account("where lower(u.username) = lower(:value)", username, null);
    }

    @Override
    @Transactional
    public Optional<AccountRecord> findBySessionTokenHash(String tokenHash, Instant now) {
        Optional<AccountRecord> account = account("""
                join auth_session auth on auth.user_id = u.id
                where auth.token_hash = :value
                  and member.workspace_id = auth.active_workspace_id
                  and auth.revoked_at is null
                  and auth.expires_at > :now
                """, tokenHash, now);
        if (account.isPresent()) {
            jdbcClient.sql("update auth_session set last_seen_at = :now where token_hash = :hash")
                    .param("now", timestamp(now))
                    .param("hash", tokenHash)
                    .update();
        }
        return account;
    }

    private Optional<AccountRecord> account(String joinAndWhere, String value, Instant now) {
        JdbcClient.StatementSpec query = jdbcClient.sql("""
                select u.id as user_id, u.username, u.display_name,
                       w.id as workspace_id, w.name as workspace_name, u.system_role, member.role,
                       credential.password_hash, credential.must_change_password
                from app_user u
                join user_credential credential on credential.user_id = u.id
                join workspace_member member on member.user_id = u.id
                join workspace w on w.id = member.workspace_id
                """ + joinAndWhere + """
                  and u.status = 'ACTIVE'
                  and w.status = 'ACTIVE'
                order by case member.role when 'OWNER' then 0 else 1 end, member.created_at
                limit 1
                """)
                .param("value", value);
        if (now != null) {
            query = query.param("now", timestamp(now));
        }
        return query.query((resultSet, rowNum) -> new AccountRecord(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("username"),
                        resultSet.getString("display_name"),
                        resultSet.getObject("workspace_id", UUID.class),
                        resultSet.getString("workspace_name"),
                        resultSet.getString("system_role"),
                        resultSet.getString("role"),
                        resultSet.getString("password_hash"),
                        resultSet.getBoolean("must_change_password")))
                .optional();
    }

    @Override
    public void saveSession(
            UUID sessionId,
            UUID userId,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt) {
        jdbcClient.sql("""
                insert into auth_session
                    (id, user_id, token_hash, created_at, expires_at, last_seen_at)
                values (:id, :userId, :tokenHash, :createdAt, :expiresAt, :createdAt)
                """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("tokenHash", tokenHash)
                .param("createdAt", timestamp(createdAt))
                .param("expiresAt", timestamp(expiresAt))
                .update();
    }

    @Override
    public void saveSession(
            UUID sessionId,
            UUID userId,
            UUID activeWorkspaceId,
            String tokenHash,
            String userAgent,
            String ipHash,
            Instant createdAt,
            Instant expiresAt) {
        jdbcClient.sql("""
                insert into auth_session
                    (id, user_id, active_workspace_id, token_hash, user_agent, ip_hash,
                     created_at, expires_at, last_seen_at)
                values (:id, :userId, :workspaceId, :tokenHash, :userAgent, :ipHash,
                        :createdAt, :expiresAt, :createdAt)
                """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("workspaceId", activeWorkspaceId)
                .param("tokenHash", tokenHash)
                .param("userAgent", userAgent)
                .param("ipHash", ipHash)
                .param("createdAt", timestamp(createdAt))
                .param("expiresAt", timestamp(expiresAt))
                .update();
    }

    @Override
    public void revokeSession(String tokenHash, Instant revokedAt) {
        jdbcClient.sql("""
                update auth_session set revoked_at = :revokedAt
                where token_hash = :tokenHash and revoked_at is null
                """)
                .param("revokedAt", timestamp(revokedAt))
                .param("tokenHash", tokenHash)
                .update();
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, String passwordHash, Instant changedAt) {
        jdbcClient.sql("""
                update user_credential
                set password_hash = :passwordHash,
                    must_change_password = false,
                    password_changed_at = :changedAt,
                    updated_at = :changedAt
                where user_id = :userId
                """)
                .param("passwordHash", passwordHash)
                .param("changedAt", timestamp(changedAt))
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                update auth_session set revoked_at = :changedAt
                where user_id = :userId and revoked_at is null
                """)
                .param("changedAt", timestamp(changedAt))
                .param("userId", userId)
                .update();
    }

    @Override
    @Transactional
    public void ensureBootstrapCredential(String username, String displayName, String passwordHash) {
        Optional<UUID> existing = jdbcClient.sql("""
                select id from app_user where lower(username) = lower(:username)
                """)
                .param("username", username)
                .query(UUID.class)
                .optional();
        UUID userId = existing.orElseGet(UUID::randomUUID);
        if (existing.isEmpty()) {
            jdbcClient.sql("""
                    insert into app_user
                        (id, username, display_name, status, system_role, created_at, updated_at)
                    values (:id, :username, :displayName, 'ACTIVE', 'SYSTEM_ADMIN', current_timestamp, current_timestamp)
                    """)
                    .param("id", userId)
                    .param("username", username)
                    .param("displayName", displayName)
                    .update();
            jdbcClient.sql("""
                    insert into workspace_member (workspace_id, user_id, role, created_at)
                    values ('00000000-0000-0000-0000-000000000001', :userId, 'OWNER', current_timestamp)
                    """)
                    .param("userId", userId)
                    .update();
        }
        jdbcClient.sql("""
                update app_user
                set display_name = :displayName,
                    status = 'ACTIVE',
                    system_role = 'SYSTEM_ADMIN',
                    updated_at = current_timestamp
                where id = :id
                """)
                .param("displayName", displayName)
                .param("id", userId)
                .update();
        jdbcClient.sql("""
                insert into user_credential (user_id, password_hash, must_change_password)
                values (:userId, :passwordHash, false)
                on conflict (user_id) do nothing
                """)
                .param("userId", userId)
                .param("passwordHash", passwordHash)
                .update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
