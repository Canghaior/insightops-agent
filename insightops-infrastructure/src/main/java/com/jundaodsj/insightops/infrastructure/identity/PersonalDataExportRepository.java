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
public class PersonalDataExportRepository {
    private final JdbcClient jdbc;
    public PersonalDataExportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Snapshot snapshot(UUID userId) {
        return new Snapshot(json("""
                select coalesce(jsonb_agg(jsonb_build_object(
                    'id',id,'username',username,'displayName',display_name,'email',email,
                    'emailVerifiedAt',email_verified_at,'status',status,'createdAt',created_at)), '[]'::jsonb)::text
                from app_user where id = :userId
                """, userId), json("""
                select coalesce(jsonb_agg(jsonb_build_object(
                    'workspaceId',workspace.id,'workspaceName',workspace.name,'workspaceSlug',workspace.slug,
                    'workspaceStatus',workspace.status,'role',member.role,'joinedAt',member.created_at)), '[]'::jsonb)::text
                from workspace_member member join workspace on workspace.id=member.workspace_id
                where member.user_id = :userId
                """, userId), json("""
                select coalesce(jsonb_agg(jsonb_build_object(
                    'id',id,'workspaceId',workspace_id,'title',title,'status',status,
                    'createdAt',created_at,'updatedAt',updated_at)), '[]'::jsonb)::text
                from conversation_session where owner_user_id = :userId
                """, userId), json("""
                select coalesce(jsonb_agg(jsonb_build_object(
                    'id',message.id,'sessionId',message.session_id,'role',message.role,
                    'content',message.content,'citations',message.citations,
                    'sequence',message.sequence_no,'createdAt',message.created_at)), '[]'::jsonb)::text
                from conversation_message message join conversation_session session on session.id=message.session_id
                where session.owner_user_id = :userId
                """, userId), json("""
                select coalesce(jsonb_agg(jsonb_build_object(
                    'id',id,'workspaceId',workspace_id,'sessionId',session_id,'traceId',trace_id,
                    'status',status,'question',question,'answer',answer,'modelProvider',model_provider,
                    'modelName',model_name,'promptTokens',prompt_tokens,'completionTokens',completion_tokens,
                    'estimatedCostCny',estimated_cost_cny,'failureCode',failure_code,
                    'failureMessage',failure_message,'startedAt',started_at,'finishedAt',finished_at,
                    'createdAt',created_at)), '[]'::jsonb)::text
                from agent_run where owner_user_id = :userId
                """, userId), json("""
                select coalesce(jsonb_agg(jsonb_build_object(
                    'id',id,'workspaceId',workspace_id,'key',memory_key,'value',memory_value,
                    'category',category,'enabled',enabled,'createdAt',created_at,'updatedAt',updated_at)), '[]'::jsonb)::text
                from user_memory where user_id = :userId
                """, userId), json("""
                select coalesce(jsonb_agg(jsonb_build_object(
                    'id',id,'workspaceId',workspace_id,'originalName',original_name,'mediaType',media_type,
                    'byteSize',byte_size,'sha256',sha256,'visibility',visibility,'status',status,
                    'pageCount',page_count,'createdAt',created_at,'updatedAt',updated_at)), '[]'::jsonb)::text
                from knowledge_upload where uploaded_by = :userId
                """, userId), json("""
                select coalesce(jsonb_agg(jsonb_build_object(
                    'documentType',document_type,'documentVersion',document_version,
                    'acceptedAt',accepted_at,'ipHash',ip_hash,
                    'userAgentHash',user_agent_hash)), '[]'::jsonb)::text
                from legal_consent where user_id = :userId
                """, userId));
    }

    public void createReady(UUID id, UUID userId, String storageKey, String tokenHash,
                            Instant expiresAt, Instant now) {
        jdbc.sql("""
                insert into personal_data_export
                    (id,user_id,status,storage_key,download_token_hash,expires_at,created_at,updated_at)
                values (:id,:userId,'READY',:storageKey,:tokenHash,:expiresAt,:now,:now)
                """).param("id", id).param("userId", userId).param("storageKey", storageKey)
                .param("tokenHash", tokenHash).param("expiresAt", timestamp(expiresAt))
                .param("now", timestamp(now)).update();
    }

    @Transactional
    public Optional<String> consume(UUID id, UUID userId, String tokenHash, Instant now) {
        return jdbc.sql("""
                update personal_data_export set status='DOWNLOADED',downloaded_at=:now,updated_at=:now,
                    download_token_hash=null
                where id=:id and user_id=:userId and status='READY'
                  and download_token_hash=:tokenHash and expires_at>:now
                returning storage_key
                """).param("id", id).param("userId", userId).param("tokenHash", tokenHash)
                .param("now", timestamp(now)).query(String.class).optional();
    }

    public List<ExpiredExport> findExpired(Instant now, int limit) {
        return jdbc.sql("""
                select id,storage_key from personal_data_export
                where status in ('READY','DOWNLOADED') and expires_at<=:now
                order by expires_at limit :limit
                """).param("now", timestamp(now)).param("limit", Math.max(1, limit))
                .query((rs, row) -> new ExpiredExport(rs.getObject("id", UUID.class),
                        rs.getString("storage_key"))).list();
    }

    public boolean markExpired(UUID id, Instant now) {
        return jdbc.sql("""
                update personal_data_export
                set status='EXPIRED',download_token_hash=null,updated_at=:now
                where id=:id and status in ('READY','DOWNLOADED') and expires_at<=:now
                """).param("id", id).param("now", timestamp(now)).update() == 1;
    }

    private String json(String sql, UUID userId) {
        return jdbc.sql(sql).param("userId", userId).query(String.class).single();
    }
    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record Snapshot(String account, String workspaces, String conversations,
                           String messages, String runs, String memories,
                           String uploads, String legalConsents) { }
    public record ExpiredExport(UUID id, String storageKey) { }
}
