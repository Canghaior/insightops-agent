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
public class PublicAccountDeletionRepository {
    private final JdbcClient jdbc;
    public PublicAccountDeletionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<UUID> personalWorkspace(UUID userId) {
        return jdbc.sql("""
                select registration.workspace_id from public_registration registration
                where registration.user_id=:userId and registration.status in ('PENDING','ACTIVE')
                  and not exists (select 1 from workspace_member member
                                  where member.workspace_id=registration.workspace_id
                                    and member.user_id<>:userId)
                """).param("userId", userId).query(UUID.class).optional();
    }

    @Transactional
    public void request(UUID userId, Instant now, Instant scheduledAt) {
        jdbc.sql("""
                insert into account_deletion_request
                    (user_id,requested_at,scheduled_at,purge_status)
                values (:userId,:now,:scheduledAt,'PENDING')
                on conflict (user_id) do update set requested_at=excluded.requested_at,
                    scheduled_at=excluded.scheduled_at,cancelled_at=null,completed_at=null,
                    purge_status='PENDING',purge_started_at=null,purged_at=null,
                    purge_error=null
                """).param("userId", userId).param("now", timestamp(now))
                .param("scheduledAt", timestamp(scheduledAt)).update();
        jdbc.sql("""
                update app_user set deletion_requested_at=:now,deletion_scheduled_at=:scheduledAt,
                    updated_at=:now where id=:userId
                """).param("userId", userId).param("now", timestamp(now))
                .param("scheduledAt", timestamp(scheduledAt)).update();
        jdbc.sql("update auth_session set revoked_at=:now where user_id=:userId and revoked_at is null")
                .param("now", timestamp(now)).param("userId", userId).update();
    }

    @Transactional
    public List<UUID> claimDue(Instant now, Instant staleBefore, int limit) {
        List<UUID> users = jdbc.sql("""
                select request.user_id from account_deletion_request request
                join public_registration registration on registration.user_id=request.user_id
                where request.scheduled_at<=:now and request.cancelled_at is null
                  and request.completed_at is null
                  and (request.purge_status in ('PENDING','FAILED')
                    or (request.purge_status='PROCESSING'
                        and (request.purge_started_at is null or request.purge_started_at<=:staleBefore)))
                order by request.scheduled_at limit :limit for update skip locked
                """).param("now", timestamp(now)).param("staleBefore", timestamp(staleBefore))
                .param("limit", Math.max(1, limit))
                .query(UUID.class).list();
        users.forEach(userId -> jdbc.sql("""
                update account_deletion_request set purge_status='PROCESSING',
                    purge_started_at=:now,purge_error=null
                where user_id=:userId
                """).param("now", timestamp(now)).param("userId", userId).update());
        return users;
    }

    public List<String> uploadStorageKeys(UUID userId) {
        return jdbc.sql("select storage_key from knowledge_upload where uploaded_by=:userId")
                .param("userId", userId).query(String.class).list();
    }

    public List<String> exportStorageKeys(UUID userId) {
        return jdbc.sql("select storage_key from personal_data_export where user_id=:userId and storage_key is not null")
                .param("userId", userId).query(String.class).list();
    }

    @Transactional
    public void complete(UUID userId, Instant now) {
        UUID workspaceId = personalWorkspaceIncludingDeleted(userId)
                .orElseThrow(() -> new IllegalStateException("Public personal workspace is missing"));
        jdbc.sql("delete from auth_session where user_id=:userId").param("userId", userId).update();
        jdbc.sql("delete from agent_run where owner_user_id=:userId").param("userId", userId).update();
        jdbc.sql("delete from conversation_session where owner_user_id=:userId").param("userId", userId).update();
        jdbc.sql("delete from user_memory where user_id=:userId").param("userId", userId).update();
        jdbc.sql("delete from user_project_watch where user_id=:userId").param("userId", userId).update();
        jdbc.sql("""
                delete from knowledge_source where id in
                    (select source_id from knowledge_upload where uploaded_by=:userId)
                """).param("userId", userId).update();
        jdbc.sql("delete from identity_token where user_id=:userId").param("userId", userId).update();
        jdbc.sql("delete from mfa_recovery_code where user_id=:userId").param("userId", userId).update();
        jdbc.sql("delete from user_mfa where user_id=:userId").param("userId", userId).update();
        jdbc.sql("delete from user_credential where user_id=:userId").param("userId", userId).update();
        jdbc.sql("""
                update personal_data_export set status='EXPIRED',download_token_hash=null,updated_at=:now
                where user_id=:userId and status<>'EXPIRED'
                """).param("now", timestamp(now)).param("userId", userId).update();
        jdbc.sql("delete from workspace where id=:workspaceId")
                .param("workspaceId", workspaceId).update();
        jdbc.sql("""
                update app_user set username=:username,display_name='Deleted account',email=null,
                    email_normalized=null,email_verified_at=null,status='DISABLED',
                    deletion_requested_at=null,deletion_scheduled_at=null,deleted_at=:now,updated_at=:now
                where id=:userId
                """).param("username", "deleted-" + userId).param("now", timestamp(now))
                .param("userId", userId).update();
        jdbc.sql("""
                update account_deletion_request set completed_at=:now,purge_status='COMPLETED',
                    purge_started_at=null,purged_at=:now,purge_error=null where user_id=:userId
                """).param("now", timestamp(now)).param("userId", userId).update();
    }

    public void fail(UUID userId, String error) {
        String safe = error == null ? "PUBLIC_ACCOUNT_PURGE_FAILED"
                : error.substring(0, Math.min(1000, error.length()));
        jdbc.sql("update account_deletion_request set purge_status='FAILED',purge_started_at=null,purge_error=:error where user_id=:userId")
                .param("error", safe).param("userId", userId).update();
    }

    private Optional<UUID> personalWorkspaceIncludingDeleted(UUID userId) {
        return jdbc.sql("select workspace_id from public_registration where user_id=:userId")
                .param("userId", userId).query(UUID.class).optional();
    }
    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
