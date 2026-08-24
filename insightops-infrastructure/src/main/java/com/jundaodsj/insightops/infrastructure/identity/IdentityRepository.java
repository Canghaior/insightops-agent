package com.jundaodsj.insightops.infrastructure.identity;

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
public class IdentityRepository {
    private final JdbcClient jdbc;

    public IdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserIdentity> findIdentity(UUID userId) {
        return jdbc.sql("""
                select user_account.id, user_account.username, user_account.display_name,
                       user_account.email, user_account.email_verified_at,
                       mfa.enabled_at as mfa_enabled_at,
                       deletion.scheduled_at as deletion_scheduled_at
                from app_user user_account
                left join user_mfa mfa on mfa.user_id = user_account.id
                left join account_deletion_request deletion
                  on deletion.user_id = user_account.id
                 and deletion.cancelled_at is null and deletion.completed_at is null
                where user_account.id = :userId
                """)
                .param("userId", userId)
                .query((rs, row) -> new UserIdentity(
                        rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("email"),
                        instant(rs, "email_verified_at"), instant(rs, "mfa_enabled_at"),
                        instant(rs, "deletion_scheduled_at")))
                .optional();
    }

    public Optional<UUID> findActiveUserByEmail(String normalizedEmail) {
        return jdbc.sql("""
                select id from app_user
                where email_normalized = :email and status = 'ACTIVE' and deleted_at is null
                """)
                .param("email", normalizedEmail)
                .query(UUID.class)
                .optional();
    }

    public boolean passwordMatchesUser(UUID userId) {
        return jdbc.sql("select count(*) from user_credential where user_id = :userId")
                .param("userId", userId).query(Long.class).single() == 1;
    }

    @Transactional
    public void setPendingEmail(UUID userId, String email, String normalizedEmail, Instant now) {
        jdbc.sql("""
                update app_user set email = :email, email_normalized = :normalized,
                    email_verified_at = null, updated_at = :now
                where id = :userId and status = 'ACTIVE'
                """)
                .param("email", email).param("normalized", normalizedEmail)
                .param("now", timestamp(now)).param("userId", userId).update();
        revokeTokens(userId, "EMAIL_VERIFICATION", now);
    }

    public void markEmailVerified(UUID userId, Instant now) {
        jdbc.sql("""
                update app_user set email_verified_at = :now, updated_at = :now
                where id = :userId and email_normalized is not null and status = 'ACTIVE'
                """)
                .param("now", timestamp(now)).param("userId", userId).update();
    }

    @Transactional
    public void saveToken(UUID id, UUID userId, String type, String tokenHash,
                          Instant expiresAt, Instant now) {
        revokeTokens(userId, type, now);
        jdbc.sql("""
                insert into identity_token
                    (id,user_id,token_type,token_hash,expires_at,created_at)
                values (:id,:userId,:type,:hash,:expiresAt,:now)
                """)
                .param("id", id).param("userId", userId).param("type", type)
                .param("hash", tokenHash).param("expiresAt", timestamp(expiresAt))
                .param("now", timestamp(now)).update();
    }

    public void revokeTokens(UUID userId, String type, Instant now) {
        jdbc.sql("""
                update identity_token set revoked_at = :now
                where user_id = :userId and token_type = :type
                  and consumed_at is null and revoked_at is null
                """)
                .param("now", timestamp(now)).param("userId", userId)
                .param("type", type).update();
    }

    @Transactional
    public Optional<UUID> consumeToken(String tokenHash, String type, Instant now) {
        return jdbc.sql("""
                update identity_token set consumed_at = :now
                where token_hash = :hash and token_type = :type
                  and consumed_at is null and revoked_at is null and expires_at > :now
                returning user_id
                """)
                .param("now", timestamp(now)).param("hash", tokenHash).param("type", type)
                .query(UUID.class).optional();
    }

    public Optional<MfaRecord> findMfa(UUID userId) {
        return jdbc.sql("""
                select user_id, secret_ciphertext, enabled_at, created_at, updated_at
                from user_mfa where user_id = :userId
                """)
                .param("userId", userId)
                .query((rs, row) -> new MfaRecord(
                        rs.getObject("user_id", UUID.class), rs.getString("secret_ciphertext"),
                        instant(rs, "enabled_at"), instant(rs, "created_at"),
                        instant(rs, "updated_at")))
                .optional();
    }

    @Transactional
    public void savePendingMfa(UUID userId, String ciphertext, Instant now) {
        jdbc.sql("""
                insert into user_mfa (user_id,secret_ciphertext,enabled_at,created_at,updated_at)
                values (:userId,:secret,null,:now,:now)
                on conflict (user_id) do update
                set secret_ciphertext = excluded.secret_ciphertext,
                    enabled_at = null, last_used_step = null,
                    updated_at = excluded.updated_at
                """)
                .param("userId", userId).param("secret", ciphertext)
                .param("now", timestamp(now)).update();
        jdbc.sql("delete from mfa_recovery_code where user_id = :userId")
                .param("userId", userId).update();
    }

    @Transactional
    public void enableMfa(UUID userId, List<String> recoveryHashes,
                          long acceptedStep, Instant now) {
        int updated = jdbc.sql("""
                update user_mfa set enabled_at = :now, last_used_step = :step, updated_at = :now
                where user_id = :userId and enabled_at is null
                """)
                .param("now", timestamp(now)).param("step", acceptedStep)
                .param("userId", userId).update();
        if (updated != 1) throw new IllegalStateException("MFA setup is not pending");
        jdbc.sql("delete from mfa_recovery_code where user_id = :userId")
                .param("userId", userId).update();
        for (String hash : recoveryHashes) {
            jdbc.sql("""
                    insert into mfa_recovery_code (id,user_id,code_hash,created_at)
                    values (:id,:userId,:hash,:now)
                    """)
                    .param("id", UUID.randomUUID()).param("userId", userId)
                    .param("hash", hash).param("now", timestamp(now)).update();
        }
    }

    @Transactional
    public boolean claimTotpStep(UUID userId, long step, Instant now) {
        return jdbc.sql("""
                update user_mfa set last_used_step = :step, updated_at = :now
                where user_id = :userId and enabled_at is not null
                  and (last_used_step is null or last_used_step < :step)
                returning user_id
                """).param("step", step).param("now", timestamp(now))
                .param("userId", userId).query(UUID.class).optional().isPresent();
    }

    @Transactional
    public void disableMfa(UUID userId) {
        jdbc.sql("delete from mfa_recovery_code where user_id = :userId")
                .param("userId", userId).update();
        jdbc.sql("delete from user_mfa where user_id = :userId")
                .param("userId", userId).update();
    }

    @Transactional
    public boolean consumeRecoveryCode(UUID userId, String codeHash, Instant now) {
        return jdbc.sql("""
                update mfa_recovery_code set used_at = :now
                where user_id = :userId and code_hash = :hash and used_at is null
                returning id
                """)
                .param("now", timestamp(now)).param("userId", userId).param("hash", codeHash)
                .query(UUID.class).optional().isPresent();
    }

    public int unusedRecoveryCodes(UUID userId) {
        return jdbc.sql("""
                select count(*) from mfa_recovery_code
                where user_id = :userId and used_at is null
                """).param("userId", userId).query(Integer.class).single();
    }

    public List<SessionRecord> listSessions(UUID userId, String currentTokenHash, Instant now) {
        return jdbc.sql("""
                select session.id, session.created_at, session.last_seen_at, session.expires_at,
                       session.user_agent, session.ip_hash, workspace.id as workspace_id,
                       workspace.name as workspace_name, session.token_hash = :currentHash as current_session
                from auth_session session
                left join workspace on workspace.id = session.active_workspace_id
                where session.user_id = :userId and session.revoked_at is null
                  and session.expires_at > :now
                order by session.last_seen_at desc
                """)
                .param("currentHash", currentTokenHash).param("userId", userId)
                .param("now", timestamp(now))
                .query((rs, row) -> new SessionRecord(
                        rs.getObject("id", UUID.class), instant(rs, "created_at"),
                        instant(rs, "last_seen_at"), instant(rs, "expires_at"),
                        rs.getString("user_agent"), rs.getString("ip_hash"),
                        rs.getObject("workspace_id", UUID.class), rs.getString("workspace_name"),
                        rs.getBoolean("current_session")))
                .list();
    }

    public boolean revokeSession(UUID userId, UUID sessionId, Instant now) {
        return jdbc.sql("""
                update auth_session set revoked_at = :now
                where id = :sessionId and user_id = :userId and revoked_at is null
                """)
                .param("now", timestamp(now)).param("sessionId", sessionId)
                .param("userId", userId).update() == 1;
    }

    public int revokeOtherSessions(UUID userId, String currentTokenHash, Instant now) {
        return jdbc.sql("""
                update auth_session set revoked_at = :now
                where user_id = :userId and token_hash <> :currentHash and revoked_at is null
                """)
                .param("now", timestamp(now)).param("userId", userId)
                .param("currentHash", currentTokenHash).update();
    }

    public void revokeAllSessions(UUID userId, Instant now) {
        jdbc.sql("""
                update auth_session set revoked_at = :now
                where user_id = :userId and revoked_at is null
                """).param("now", timestamp(now)).param("userId", userId).update();
    }

    @Transactional
    public void rehomeSessionsAfterWorkspaceRemoval(UUID userId, UUID workspaceId, Instant now) {
        jdbc.sql("""
                update auth_session session set active_workspace_id = (
                    select member.workspace_id
                    from workspace_member member
                    join workspace on workspace.id = member.workspace_id
                    where member.user_id = :userId
                      and member.workspace_id <> :workspaceId
                      and workspace.status = 'ACTIVE'
                    order by case member.role when 'OWNER' then 0 else 1 end, member.created_at
                    limit 1
                )
                where session.user_id = :userId
                  and session.active_workspace_id = :workspaceId
                  and session.revoked_at is null
                """).param("userId", userId).param("workspaceId", workspaceId).update();
        jdbc.sql("""
                update auth_session set revoked_at = :now
                where user_id = :userId and active_workspace_id is null and revoked_at is null
                """).param("now", timestamp(now)).param("userId", userId).update();
    }

    @Transactional
    public void resetPassword(UUID userId, String passwordHash, Instant now) {
        jdbc.sql("""
                update user_credential set password_hash = :hash,
                    must_change_password = false, password_changed_at = :now, updated_at = :now
                where user_id = :userId
                """).param("hash", passwordHash).param("now", timestamp(now))
                .param("userId", userId).update();
        revokeAllSessions(userId, now);
        revokeTokens(userId, "PASSWORD_RESET", now);
    }

    @Transactional
    public void requestDeletion(UUID userId, Instant now, Instant scheduledAt) {
        jdbc.sql("""
                insert into account_deletion_request (user_id,requested_at,scheduled_at)
                values (:userId,:now,:scheduled)
                on conflict (user_id) do update
                set requested_at = excluded.requested_at, scheduled_at = excluded.scheduled_at,
                    cancelled_at = null, completed_at = null
                """)
                .param("userId", userId).param("now", timestamp(now))
                .param("scheduled", timestamp(scheduledAt)).update();
        jdbc.sql("""
                update app_user set deletion_requested_at = :now,
                    deletion_scheduled_at = :scheduled, updated_at = :now
                where id = :userId
                """).param("userId", userId).param("now", timestamp(now))
                .param("scheduled", timestamp(scheduledAt)).update();
        revokeAllSessions(userId, now);
    }

    @Transactional
    public boolean cancelDeletion(UUID userId, Instant now) {
        int updated = jdbc.sql("""
                update account_deletion_request set cancelled_at = :now
                where user_id = :userId and cancelled_at is null and completed_at is null
                """).param("userId", userId).param("now", timestamp(now)).update();
        if (updated == 1) {
            jdbc.sql("""
                    update app_user set deletion_requested_at = null,
                        deletion_scheduled_at = null, updated_at = :now where id = :userId
                    """).param("userId", userId).param("now", timestamp(now)).update();
        }
        return updated == 1;
    }

    @Transactional
    public int completeDueDeletions(Instant now, int limit) {
        List<UUID> users = jdbc.sql("""
                select deletion.user_id from account_deletion_request deletion
                where deletion.scheduled_at <= :now
                  and deletion.cancelled_at is null and deletion.completed_at is null
                  and not exists (
                    select 1 from workspace_member mine
                    join workspace on workspace.id = mine.workspace_id
                    where mine.user_id = deletion.user_id and mine.role = 'OWNER'
                      and workspace.status = 'ACTIVE'
                      and not exists (
                        select 1 from workspace_member other
                        where other.workspace_id = mine.workspace_id
                          and other.role = 'OWNER' and other.user_id <> deletion.user_id))
                order by deletion.scheduled_at limit :limit for update skip locked
                """).param("now", timestamp(now)).param("limit", limit)
                .query(UUID.class).list();
        for (UUID userId : users) anonymize(userId, now);
        return users.size();
    }

    private void anonymize(UUID userId, Instant now) {
        revokeAllSessions(userId, now);
        jdbc.sql("delete from identity_token where user_id = :userId").param("userId", userId).update();
        jdbc.sql("delete from mfa_recovery_code where user_id = :userId").param("userId", userId).update();
        jdbc.sql("delete from user_mfa where user_id = :userId").param("userId", userId).update();
        jdbc.sql("delete from user_credential where user_id = :userId").param("userId", userId).update();
        jdbc.sql("delete from workspace_member where user_id = :userId").param("userId", userId).update();
        String suffix = userId.toString();
        jdbc.sql("""
                update app_user set username = :username, display_name = 'Deleted account',
                    email = null, email_normalized = null, email_verified_at = null,
                    status = 'DISABLED', deletion_requested_at = null,
                    deletion_scheduled_at = null, deleted_at = :now, updated_at = :now
                where id = :userId
                """).param("username", "deleted-" + suffix)
                .param("now", timestamp(now)).param("userId", userId).update();
        jdbc.sql("""
                update account_deletion_request set completed_at = :now where user_id = :userId
                """).param("now", timestamp(now)).param("userId", userId).update();
    }

    public Optional<RateState> rateState(String scope, String keyHash) {
        return jdbc.sql("""
                select failures,window_started_at,locked_until,updated_at
                from auth_rate_limit where scope = :scope and key_hash = :key
                """).param("scope", scope).param("key", keyHash)
                .query((rs, row) -> new RateState(rs.getInt("failures"),
                        instant(rs, "window_started_at"), instant(rs, "locked_until"),
                        instant(rs, "updated_at")))
                .optional();
    }

    @Transactional
    public void saveRateState(String scope, String keyHash, RateState state) {
        jdbc.sql("""
                insert into auth_rate_limit
                    (scope,key_hash,failures,window_started_at,locked_until,updated_at)
                values (:scope,:key,:failures,:windowStart,:lockedUntil,:updatedAt)
                on conflict (scope,key_hash) do update
                set failures = excluded.failures, window_started_at = excluded.window_started_at,
                    locked_until = excluded.locked_until, updated_at = excluded.updated_at
                """).param("scope", scope).param("key", keyHash)
                .param("failures", state.failures())
                .param("windowStart", timestamp(state.windowStartedAt()))
                .param("lockedUntil", nullableTimestamp(state.lockedUntil()))
                .param("updatedAt", timestamp(state.updatedAt())).update();
    }

    public void clearRateState(String scope, String keyHash) {
        jdbc.sql("delete from auth_rate_limit where scope = :scope and key_hash = :key")
                .param("scope", scope).param("key", keyHash).update();
    }
    @Transactional
    public RateState recordRateFailure(String scope, String keyHash, Instant now,
                                       int windowMinutes, int maximum, int lockMinutes) {
        jdbc.sql("""
                select hashtextextended(:lockKey, 0)
                from (select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))) held
                """).param("lockKey", scope + ':' + keyHash).query(Long.class).single();
        RateState current = rateState(scope, keyHash).orElse(null);
        boolean expired = current == null || !current.windowStartedAt()
                .plusSeconds(Math.max(1, windowMinutes) * 60L).isAfter(now);
        int failures = expired ? 1 : current.failures() + 1;
        Instant lockedUntil;
        if (!expired && current.lockedUntil() != null && current.lockedUntil().isAfter(now)) {
            lockedUntil = current.lockedUntil();
        } else if (failures >= Math.max(1, maximum)) {
            lockedUntil = now.plusSeconds(Math.max(1, lockMinutes) * 60L);
        } else {
            lockedUntil = null;
        }
        RateState next = new RateState(failures, expired ? now : current.windowStartedAt(),
                lockedUntil, now);
        saveRateState(scope, keyHash, next);
        return next;
    }


    public void purgeRateStates(Instant before) {
        jdbc.sql("delete from auth_rate_limit where updated_at < :before")
                .param("before", timestamp(before)).update();
    }

    public void enqueueMail(UUID id, String recipient, String template, String subject,
                            String bodyCiphertext, Instant now) {
        jdbc.sql("""
                insert into identity_mail_outbox
                    (id,recipient_email,template_type,subject,body_ciphertext,status,
                     scheduled_at,created_at,updated_at)
                values (:id,:recipient,:template,:subject,:body,'PENDING',:now,:now,:now)
                """).param("id", id).param("recipient", recipient).param("template", template)
                .param("subject", subject).param("body", bodyCiphertext)
                .param("now", timestamp(now)).update();
    }

    @Transactional
    public List<MailTask> claimMail(Instant now, Instant expiredLease, int limit, String workerId) {
        List<UUID> ids = jdbc.sql("""
                select id from identity_mail_outbox
                where ((status in ('PENDING','RETRY_WAIT') and scheduled_at <= :now)
                    or (status = 'SENDING' and locked_at < :expiredLease))
                order by scheduled_at limit :limit for update skip locked
                """).param("now", timestamp(now)).param("expiredLease", timestamp(expiredLease))
                .param("limit", limit).query(UUID.class).list();
        if (ids.isEmpty()) return List.of();
        return ids.stream().map(id -> jdbc.sql("""
                update identity_mail_outbox set status = 'SENDING', attempts = attempts + 1,
                    locked_at = :now, locked_by = :workerId, updated_at = :now
                where id = :id
                returning id,recipient_email,template_type,subject,body_ciphertext,attempts,max_attempts
                """).param("now", timestamp(now)).param("workerId", workerId).param("id", id)
                .query((rs, row) -> new MailTask(rs.getObject("id", UUID.class),
                        rs.getString("recipient_email"), rs.getString("template_type"),
                        rs.getString("subject"), rs.getString("body_ciphertext"),
                        rs.getInt("attempts"), rs.getInt("max_attempts"))).single()).toList();
    }

    public void completeMail(UUID id, Instant now) {
        jdbc.sql("""
                update identity_mail_outbox set status = 'SENT', sent_at = :now,
                    locked_at = null, locked_by = null, last_error = null, updated_at = :now
                where id = :id and status = 'SENDING'
                """).param("now", timestamp(now)).param("id", id).update();
    }

    public void failMail(UUID id, String error, Instant retryAt, boolean terminal, Instant now) {
        jdbc.sql("""
                update identity_mail_outbox set status = :status, scheduled_at = :retryAt,
                    locked_at = null, locked_by = null, last_error = :error, updated_at = :now
                where id = :id and status = 'SENDING'
                """).param("status", terminal ? "FAILED" : "RETRY_WAIT")
                .param("retryAt", timestamp(retryAt)).param("error", truncate(error, 1000))
                .param("now", timestamp(now)).param("id", id).update();
    }

    private static String truncate(String value, int maximum) {
        String safe = value == null || value.isBlank() ? "MAIL_DELIVERY_FAILED" : value;
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static OffsetDateTime nullableTimestamp(Instant value) {
        return value == null ? null : timestamp(value);
    }

    public record UserIdentity(UUID userId, String username, String displayName, String email,
                               Instant emailVerifiedAt, Instant mfaEnabledAt,
                               Instant deletionScheduledAt) {
        public boolean emailVerified() { return emailVerifiedAt != null; }
        public boolean mfaEnabled() { return mfaEnabledAt != null; }
    }

    public record MfaRecord(UUID userId, String secretCiphertext, Instant enabledAt,
                            Instant createdAt, Instant updatedAt) {
        public boolean enabled() { return enabledAt != null; }
    }

    public record SessionRecord(UUID id, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
                                String userAgent, String ipHash, UUID workspaceId,
                                String workspaceName, boolean current) { }

    public record RateState(int failures, Instant windowStartedAt, Instant lockedUntil,
                            Instant updatedAt) { }

    public record MailTask(UUID id, String recipient, String template, String subject,
                           String bodyCiphertext, int attempts, int maxAttempts) { }
}
