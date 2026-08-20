package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadQuotaExceededException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKnowledgeUploadStore implements KnowledgeUploadStore {
    private final JdbcClient jdbc;

    public JdbcKnowledgeUploadStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public UploadRecord create(CreateUpload command, Instant now) {
        jdbc.sql("select id from workspace where id=:workspaceId for update")
                .param("workspaceId", command.workspaceId()).query(UUID.class).single();
        long used = jdbc.sql("""
                select coalesce(sum(byte_size), 0) from knowledge_upload
                where workspace_id=:workspaceId
                """).param("workspaceId", command.workspaceId()).query(Long.class).single();
        if (command.workspaceQuotaBytes() < 1 || used > command.workspaceQuotaBytes()
                || command.byteSize() > command.workspaceQuotaBytes() - used) {
            throw new KnowledgeUploadQuotaExceededException();
        }

        String safeName = URLEncoder.encode(command.originalName(), StandardCharsets.UTF_8).replace("+", "%20");
        String uploadUrl = "upload://" + command.uploadId() + "/" + safeName;
        jdbc.sql("""
                insert into knowledge_source
                    (id, workspace_id, project_id, source_key, name, source_type,
                     root_url, discovery_url, allowed_host, allowed_path_prefix,
                     trust_tier, sync_interval_hours, enabled, status, next_sync_at,
                     created_at, updated_at)
                values (:sourceId, :workspaceId, :projectId, :sourceKey, :name, 'USER_UPLOAD',
                        :url, :url, 'uploads.internal', '/', 'T2_USER_UPLOAD', 720,
                        true, 'NEVER', :now, :now, :now)
                """).param("sourceId", command.sourceId()).param("workspaceId", command.workspaceId())
                .param("projectId", command.projectId()).param("sourceKey", "upload-" + command.uploadId())
                .param("name", command.originalName()).param("url", uploadUrl).param("now", timestamp(now)).update();
        jdbc.sql("""
                insert into knowledge_upload
                    (id, source_id, workspace_id, uploaded_by, original_name, storage_key,
                     media_type, byte_size, sha256, visibility, status, created_at, updated_at)
                values (:id, :sourceId, :workspaceId, :uploadedBy, :name, :storageKey,
                        :mediaType, :byteSize, :sha256, :visibility, 'PENDING', :now, :now)
                """).param("id", command.uploadId()).param("sourceId", command.sourceId())
                .param("workspaceId", command.workspaceId()).param("uploadedBy", command.uploadedBy())
                .param("name", command.originalName()).param("storageKey", command.storageKey())
                .param("mediaType", command.mediaType()).param("byteSize", command.byteSize())
                .param("sha256", command.sha256()).param("visibility", command.visibility())
                .param("now", timestamp(now)).update();
        return findVisible(command.workspaceId(), command.uploadedBy(), false, command.uploadId()).orElseThrow();
    }

    @Override
    public List<UploadRecord> listVisible(UUID workspaceId, UUID userId, boolean systemAdmin) {
        return jdbc.sql("""
                select upload.id, upload.source_id, source.project_id, project.repository_name as project_name,
                       upload.uploaded_by, app_user.display_name as uploader_name, upload.original_name,
                       upload.media_type, upload.byte_size, upload.sha256, upload.visibility,
                       upload.status, upload.page_count, upload.error_message,
                       job.current_url, job.heartbeat_at, job.lease_expires_at,
                       upload.created_at, upload.updated_at
                from knowledge_upload upload
                join knowledge_source source on source.id=upload.source_id
                join tracked_project project on project.id=source.project_id
                join app_user on app_user.id=upload.uploaded_by
                left join lateral (
                    select current_url, heartbeat_at, lease_expires_at
                    from knowledge_collection_job where source_id=source.id
                    order by started_at desc limit 1
                ) job on true
                where upload.workspace_id=:workspaceId
                  and (:systemAdmin or upload.uploaded_by=:userId or upload.visibility='WORKSPACE')
                order by upload.created_at desc
                """).param("workspaceId", workspaceId).param("userId", userId)
                .param("systemAdmin", systemAdmin).query(JdbcKnowledgeUploadStore::record).list();
    }

    @Override
    public Optional<UploadRecord> findVisible(UUID workspaceId, UUID userId, boolean systemAdmin, UUID uploadId) {
        return listVisible(workspaceId, userId, systemAdmin).stream()
                .filter(upload -> upload.uploadId().equals(uploadId)).findFirst();
    }

    @Override
    @Transactional
    public Optional<DeleteTarget> delete(UUID workspaceId, UUID userId, boolean systemAdmin, UUID uploadId) {
        Optional<DeleteTarget> target = jdbc.sql("""
                select storage_key, source_id from knowledge_upload
                where id=:uploadId and workspace_id=:workspaceId
                  and (:systemAdmin or uploaded_by=:userId)
                for update
                """).param("uploadId", uploadId).param("workspaceId", workspaceId)
                .param("userId", userId).param("systemAdmin", systemAdmin)
                .query((rs, row) -> new DeleteTarget(rs.getString("storage_key"),
                        rs.getObject("source_id", UUID.class))).optional();
        target.ifPresent(value -> jdbc.sql("delete from knowledge_source where id=:sourceId")
                .param("sourceId", value.sourceId()).update());
        return target;
    }

    @Override
    public long workspaceBytes(UUID workspaceId) {
        return jdbc.sql("select coalesce(sum(byte_size), 0) from knowledge_upload where workspace_id=:workspaceId")
                .param("workspaceId", workspaceId).query(Long.class).single();
    }

    private static UploadRecord record(ResultSet rs, int row) throws SQLException {
        return new UploadRecord(rs.getObject("id", UUID.class), rs.getObject("source_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("project_name"),
                rs.getObject("uploaded_by", UUID.class), rs.getString("uploader_name"),
                rs.getString("original_name"), rs.getString("media_type"), rs.getLong("byte_size"),
                rs.getString("sha256"), rs.getString("visibility"), rs.getString("status"),
                rs.getInt("page_count"), rs.getString("error_message"), rs.getString("current_url"),
                instant(rs, "heartbeat_at"), instant(rs, "lease_expires_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static OffsetDateTime timestamp(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
