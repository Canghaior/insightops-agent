package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKnowledgeStore implements KnowledgeStore {
    private static final String CHUNK_PIPELINE_VERSION = "2";
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcKnowledgeStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public List<SourceTask> claimDueSources(Instant now, Duration lockDuration, int limit) {
        List<SourceTask> due = jdbc.sql("""
                select source.id, source.workspace_id, source.project_id,
                       project.repository_name as project_name, source.source_key,
                       source.name, source.source_type, source.root_url, source.discovery_url,
                       source.allowed_host, source.allowed_path_prefix, source.trust_tier,
                       source.consecutive_failures
                from knowledge_source source
                join tracked_project project on project.id = source.project_id
                where source.enabled = true
                  and source.next_sync_at <= :now
                  and (source.locked_until is null or source.locked_until < :now)
                order by source.next_sync_at, project.priority
                for update of source skip locked
                limit :limit
                """)
                .param("now", timestamp(now))
                .param("limit", Math.max(1, limit))
                .query((rs, rowNum) -> sourceTask(rs, UUID.randomUUID()))
                .list();
        List<SourceTask> claimed = new ArrayList<>();
        for (SourceTask source : due) {
            jdbc.sql("""
                    update knowledge_collection_job
                    set status='FAILED', error_code='LOCK_EXPIRED',
                        error_message='Previous worker lease expired before completion', finished_at=:now
                    where source_id=:sourceId and status='RUNNING'
                    """)
                    .param("sourceId", source.sourceId())
                    .param("now", timestamp(now))
                    .update();
            int updated = jdbc.sql("""
                    update knowledge_source
                    set status='RUNNING', locked_until=:lockedUntil, last_error=null, updated_at=:now
                    where id=:id and (locked_until is null or locked_until < :now)
                    """)
                    .param("id", source.sourceId())
                    .param("now", timestamp(now))
                    .param("lockedUntil", timestamp(now.plus(lockDuration)))
                    .update();
            if (updated == 0) continue;
            jdbc.sql("""
                    insert into knowledge_collection_job (id, source_id, status, started_at, created_at)
                    values (:id, :sourceId, 'RUNNING', :startedAt, :startedAt)
                    """)
                    .param("id", source.jobId())
                    .param("sourceId", source.sourceId())
                    .param("startedAt", timestamp(now))
                    .update();
            claimed.add(source);
        }
        return List.copyOf(claimed);
    }

    @Override
    @Transactional
    public SyncResult completeSuccessfulSync(SourceTask source, List<DocumentPage> pages,
                                             Instant completedAt, Instant nextSyncAt) {
        int newDocuments = 0;
        int changedDocuments = 0;
        int unchangedDocuments = 0;
        int chunkCount = 0;
        for (DocumentPage page : pages) {
            Optional<DocumentState> existing = findDocument(source.sourceId(), page.canonicalUrl());
            UUID documentId;
            if (existing.isEmpty()) {
                documentId = UUID.randomUUID();
                jdbc.sql("""
                        insert into knowledge_document
                            (id, source_id, canonical_url, title, language, version_label,
                             etag, last_modified, active, first_seen_at, last_seen_at,
                             created_at, updated_at)
                        values
                            (:id, :sourceId, :url, :title, :language, :versionLabel,
                             :etag, :lastModified, true, :now, :now, :now, :now)
                        """)
                        .param("id", documentId)
                        .param("sourceId", source.sourceId())
                        .param("url", page.canonicalUrl())
                        .param("title", page.title())
                        .param("language", page.language())
                        .param("versionLabel", page.versionLabel())
                        .param("etag", page.etag())
                        .param("lastModified", page.lastModified())
                        .param("now", timestamp(completedAt))
                        .update();
                newDocuments++;
            } else {
                documentId = existing.orElseThrow().id();
                jdbc.sql("""
                        update knowledge_document
                        set title=:title, language=:language, version_label=:versionLabel,
                            etag=:etag, last_modified=:lastModified, active=true,
                            last_seen_at=:now, updated_at=:now
                        where id=:id
                        """)
                        .param("id", documentId)
                        .param("title", page.title())
                        .param("language", page.language())
                        .param("versionLabel", page.versionLabel())
                        .param("etag", page.etag())
                        .param("lastModified", page.lastModified())
                        .param("now", timestamp(completedAt))
                        .update();
                if (page.contentSha256().equals(existing.orElseThrow().contentSha256())) {
                    UUID revisionId = existing.orElseThrow().currentRevisionId();
                    if (!chunksAreCurrent(revisionId, page.chunks())) {
                        chunkCount += replaceChunks(revisionId, source, page);
                    }
                    unchangedDocuments++;
                    continue;
                }
                changedDocuments++;
            }
            Optional<UUID> priorRevision = findRevision(documentId, page.contentSha256());
            UUID revisionId = priorRevision.orElseGet(UUID::randomUUID);
            if (priorRevision.isEmpty()) {
                jdbc.sql("""
                    insert into knowledge_revision
                        (id, document_id, content_sha256, content_text, character_count, collected_at)
                    values (:id, :documentId, :hash, :content, :characters, :collectedAt)
                    """)
                    .param("id", revisionId)
                    .param("documentId", documentId)
                    .param("hash", page.contentSha256())
                    .param("content", page.contentText())
                    .param("characters", page.contentText().length())
                    .param("collectedAt", timestamp(completedAt))
                    .update();
                chunkCount += insertChunks(revisionId, source, page);
            } else if (!chunksAreCurrent(revisionId, page.chunks())) {
                chunkCount += replaceChunks(revisionId, source, page);
            }
            jdbc.sql("update knowledge_document set current_revision_id=:revisionId where id=:id")
                    .param("revisionId", revisionId)
                    .param("id", documentId)
                    .update();
        }
        SyncResult result = new SyncResult(pages.size(), newDocuments, changedDocuments,
                unchangedDocuments, chunkCount);
        jdbc.sql("""
                update knowledge_collection_job
                set status='SUCCEEDED', page_count=:pages, new_document_count=:newDocuments,
                    changed_document_count=:changedDocuments,
                    unchanged_document_count=:unchangedDocuments, chunk_count=:chunks,
                    finished_at=:finishedAt
                where id=:id and status='RUNNING'
                """)
                .param("id", source.jobId())
                .param("pages", result.pageCount())
                .param("newDocuments", result.newDocuments())
                .param("changedDocuments", result.changedDocuments())
                .param("unchangedDocuments", result.unchangedDocuments())
                .param("chunks", result.chunkCount())
                .param("finishedAt", timestamp(completedAt))
                .update();
        jdbc.sql("""
                update knowledge_source
                set status='SUCCEEDED', last_sync_at=:completedAt, next_sync_at=:nextSyncAt,
                    locked_until=null, consecutive_failures=0, last_error=null, updated_at=:completedAt
                where id=:id
                """)
                .param("id", source.sourceId())
                .param("completedAt", timestamp(completedAt))
                .param("nextSyncAt", timestamp(nextSyncAt))
                .update();
        return result;
    }

    @Override
    @Transactional
    public void completeFailedSync(SourceTask source, String errorCode, String errorMessage,
                                   Instant failedAt, Instant nextRetryAt) {
        String message = truncate(errorMessage, 1000);
        jdbc.sql("""
                update knowledge_collection_job
                set status='FAILED', error_code=:errorCode, error_message=:errorMessage,
                    finished_at=:finishedAt
                where id=:id and status='RUNNING'
                """)
                .param("id", source.jobId())
                .param("errorCode", truncate(errorCode, 64))
                .param("errorMessage", message)
                .param("finishedAt", timestamp(failedAt))
                .update();
        jdbc.sql("""
                update knowledge_source
                set status='RETRY_WAIT', next_sync_at=:nextRetryAt, locked_until=null,
                    consecutive_failures=consecutive_failures+1, last_error=:lastError,
                    updated_at=:failedAt
                where id=:id
                """)
                .param("id", source.sourceId())
                .param("nextRetryAt", timestamp(nextRetryAt))
                .param("lastError", message)
                .param("failedAt", timestamp(failedAt))
                .update();
    }

    @Override
    public List<SourceStatus> sourceStatus(UUID workspaceId) {
        return jdbc.sql("""
                select source.id, source.project_id, project.repository_name as project_name,
                       source.source_key, source.name, source.source_type, source.root_url,
                       source.trust_tier, source.enabled, source.status, source.last_sync_at,
                       source.next_sync_at, source.consecutive_failures, source.last_error,
                       (select count(*) from knowledge_document document
                        where document.source_id=source.id and document.active=true) as document_count,
                       (select count(*) from knowledge_revision revision
                        join knowledge_document document on document.id=revision.document_id
                        where document.source_id=source.id) as revision_count,
                       (select count(*) from knowledge_chunk chunk
                        join knowledge_document document on document.current_revision_id=chunk.revision_id
                        where document.source_id=source.id and document.active=true) as chunk_count,
                       job.id as job_id, job.status as job_status, job.page_count,
                       job.new_document_count, job.changed_document_count,
                       job.unchanged_document_count, job.chunk_count as job_chunk_count,
                       job.error_code, job.error_message, job.started_at, job.finished_at
                from knowledge_source source
                join tracked_project project on project.id=source.project_id
                left join lateral (
                    select * from knowledge_collection_job candidate
                    where candidate.source_id=source.id
                    order by candidate.started_at desc limit 1
                ) job on true
                where source.workspace_id=:workspaceId
                order by project.priority, source.name
                """)
                .param("workspaceId", workspaceId)
                .query((rs, rowNum) -> sourceStatus(rs, rowNum))
                .list();
    }

    @Override
    @Transactional
    public boolean requestSync(UUID workspaceId, UUID sourceId, Instant now) {
        return jdbc.sql("""
                update knowledge_source
                set next_sync_at=:now, locked_until=null,
                    status='RETRY_WAIT',
                    updated_at=:now
                where id=:sourceId and workspace_id=:workspaceId and enabled=true
                  and status <> 'RUNNING'
                """)
                .param("sourceId", sourceId)
                .param("workspaceId", workspaceId)
                .param("now", timestamp(now))
                .update() > 0;
    }

    private Optional<DocumentState> findDocument(UUID sourceId, String canonicalUrl) {
        return jdbc.sql("""
                select document.id, document.current_revision_id, revision.content_sha256
                from knowledge_document document
                left join knowledge_revision revision on revision.id=document.current_revision_id
                where document.source_id=:sourceId and document.canonical_url=:url
                """)
                .param("sourceId", sourceId)
                .param("url", canonicalUrl)
                .query((rs, rowNum) -> new DocumentState(
                        rs.getObject("id", UUID.class),
                        rs.getObject("current_revision_id", UUID.class),
                        rs.getString("content_sha256")))
                .optional();
    }

    private boolean chunksAreCurrent(UUID revisionId, List<DocumentChunk> chunks) {
        if (revisionId == null) {
            return false;
        }
        List<ChunkState> stored = jdbc.sql("""
                select content_sha256, metadata ->> 'chunkPipelineVersion' as pipeline_version
                from knowledge_chunk
                where revision_id=:revisionId
                order by chunk_index
                """)
                .param("revisionId", revisionId)
                .query((rs, rowNum) -> new ChunkState(
                        rs.getString("content_sha256"), rs.getString("pipeline_version")))
                .list();
        if (stored.size() != chunks.size()) {
            return false;
        }
        for (int index = 0; index < chunks.size(); index++) {
            if (!chunks.get(index).contentSha256().equals(stored.get(index).contentSha256())
                    || !CHUNK_PIPELINE_VERSION.equals(stored.get(index).pipelineVersion())) {
                return false;
            }
        }
        return true;
    }

    private int replaceChunks(UUID revisionId, SourceTask source, DocumentPage page) {
        jdbc.sql("delete from knowledge_chunk where revision_id=:revisionId")
                .param("revisionId", revisionId)
                .update();
        return insertChunks(revisionId, source, page);
    }

    private int insertChunks(UUID revisionId, SourceTask source, DocumentPage page) {
        for (DocumentChunk chunk : page.chunks()) {
            jdbc.sql("""
                    insert into knowledge_chunk
                        (id, revision_id, chunk_index, heading_path, content,
                         content_sha256, character_count, estimated_tokens, metadata)
                    values
                        (:id, :revisionId, :chunkIndex, :headingPath, :content,
                         :hash, :characters, :tokens, cast(:metadata as jsonb))
                    """)
                    .param("id", UUID.randomUUID())
                    .param("revisionId", revisionId)
                    .param("chunkIndex", chunk.index())
                    .param("headingPath", chunk.headingPath())
                    .param("content", chunk.content())
                    .param("hash", chunk.contentSha256())
                    .param("characters", chunk.characterCount())
                    .param("tokens", chunk.estimatedTokens())
                    .param("metadata", metadata(source, page, chunk))
                    .update();
        }
        return page.chunks().size();
    }

    private Optional<UUID> findRevision(UUID documentId, String contentSha256) {
        return jdbc.sql("""
                select id from knowledge_revision
                where document_id=:documentId and content_sha256=:hash
                """)
                .param("documentId", documentId)
                .param("hash", contentSha256)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .optional();
    }

    private SourceTask sourceTask(ResultSet rs, UUID jobId) throws SQLException {
        return new SourceTask(jobId, rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_name"), rs.getString("source_key"), rs.getString("name"),
                rs.getString("source_type"), rs.getString("root_url"), rs.getString("discovery_url"),
                rs.getString("allowed_host"), rs.getString("allowed_path_prefix"),
                rs.getString("trust_tier"), rs.getInt("consecutive_failures"));
    }

    private SourceStatus sourceStatus(ResultSet rs, int rowNum) throws SQLException {
        UUID jobId = rs.getObject("job_id", UUID.class);
        JobStatus job = jobId == null ? null : new JobStatus(jobId, rs.getString("job_status"),
                rs.getInt("page_count"), rs.getInt("new_document_count"),
                rs.getInt("changed_document_count"), rs.getInt("unchanged_document_count"),
                rs.getInt("job_chunk_count"), rs.getString("error_code"),
                rs.getString("error_message"), instant(rs, "started_at"), instant(rs, "finished_at"));
        return new SourceStatus(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_name"), rs.getString("source_key"), rs.getString("name"),
                rs.getString("source_type"), rs.getString("root_url"), rs.getString("trust_tier"),
                rs.getBoolean("enabled"), rs.getString("status"), instant(rs, "last_sync_at"),
                instant(rs, "next_sync_at"), rs.getInt("consecutive_failures"),
                rs.getString("last_error"), rs.getLong("document_count"),
                rs.getLong("revision_count"), rs.getLong("chunk_count"), job);
    }

    private String metadata(SourceTask source, DocumentPage page, DocumentChunk chunk) {
        try {
            return json.writeValueAsString(Map.ofEntries(
                    Map.entry("workspaceId", source.workspaceId().toString()),
                    Map.entry("projectId", source.projectId().toString()),
                    Map.entry("projectName", source.projectName()),
                    Map.entry("sourceKey", source.sourceKey()),
                    Map.entry("sourceType", source.sourceType()),
                    Map.entry("canonicalUrl", page.canonicalUrl()),
                    Map.entry("title", page.title()),
                    Map.entry("headingPath", chunk.headingPath() == null ? "" : chunk.headingPath()),
                    Map.entry("language", page.language()),
                    Map.entry("version", page.versionLabel() == null ? "" : page.versionLabel()),
                    Map.entry("trustTier", source.trustTier()),
                    Map.entry("chunkPipelineVersion", CHUNK_PIPELINE_VERSION)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize knowledge chunk metadata", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        String text = value == null || value.isBlank() ? "Unknown collection failure" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private record DocumentState(UUID id, UUID currentRevisionId, String contentSha256) { }
    private record ChunkState(String contentSha256, String pipelineVersion) { }
}
