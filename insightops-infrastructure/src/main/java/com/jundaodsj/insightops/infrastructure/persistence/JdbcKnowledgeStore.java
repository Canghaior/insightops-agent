package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.KnowledgeCollectionLeaseLostException;
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
                       source.sync_interval_hours, source.consecutive_failures,
                       source.fetch_etag, source.fetch_last_modified,
                       upload.storage_key as upload_storage_key,
                       upload.original_name as upload_original_name,
                       upload.media_type as upload_media_type,
                       upload.visibility as upload_visibility,
                       upload.uploaded_by as upload_user_id
                from knowledge_source source
                join tracked_project project on project.id = source.project_id
                left join knowledge_upload upload on upload.source_id = source.id
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
            Instant leaseExpiresAt = now.plus(lockDuration);
            jdbc.sql("""
                    update knowledge_collection_job
                    set status='FAILED', error_code='LOCK_EXPIRED',
                        error_message='Previous worker lease expired before completion',
                        finished_at=:now, lease_expires_at=coalesce(lease_expires_at, :now)
                    where source_id=:sourceId and status='RUNNING'
                    """)
                    .param("sourceId", source.sourceId())
                    .param("now", timestamp(now))
                    .update();
            int updated = jdbc.sql("""
                    update knowledge_source
                    set status='RUNNING', locked_until=:lockedUntil, lock_token=:lockToken,
                        last_error=null, updated_at=:now
                    where id=:id and (locked_until is null or locked_until < :now)
                    """)
                    .param("id", source.sourceId())
                    .param("now", timestamp(now))
                    .param("lockedUntil", timestamp(leaseExpiresAt))
                    .param("lockToken", source.jobId())
                    .update();
            if (updated == 0) continue;
            jdbc.sql("""
                    update knowledge_upload set status='PROCESSING', error_message=null, updated_at=:now
                    where source_id=:sourceId and status in ('PENDING', 'FAILED', 'PROCESSING')
                    """)
                    .param("sourceId", source.sourceId()).param("now", timestamp(now))
                    .update();
            jdbc.sql("""
                    insert into knowledge_collection_job
                        (id, source_id, status, heartbeat_at, lease_expires_at, started_at, created_at)
                    values
                        (:id, :sourceId, 'RUNNING', :startedAt, :leaseExpiresAt, :startedAt, :startedAt)
                    """)
                    .param("id", source.jobId())
                    .param("sourceId", source.sourceId())
                    .param("startedAt", timestamp(now))
                    .param("leaseExpiresAt", timestamp(leaseExpiresAt))
                    .update();
            claimed.add(source);
        }
        return List.copyOf(claimed);
    }

    @Override
    @Transactional
    public void updateCollectionProgress(SourceTask source, CollectionProgress progress,
                                         Instant heartbeatAt, Duration lockDuration) {
        Instant leaseExpiresAt = heartbeatAt.plus(lockDuration);
        int jobUpdated = jdbc.sql("""
                update knowledge_collection_job
                set max_page_count=:maxPages,
                    discovered_url_count=greatest(discovered_url_count, :discoveredUrls),
                    visited_url_count=greatest(visited_url_count, :visitedUrls),
                    page_count=greatest(page_count, :collectedPages),
                    current_url=:currentUrl, heartbeat_at=:heartbeatAt,
                    lease_expires_at=:leaseExpiresAt
                where id=:jobId and source_id=:sourceId and status='RUNNING'
                """)
                .param("jobId", source.jobId())
                .param("sourceId", source.sourceId())
                .param("maxPages", Math.max(0, progress.maxPageCount()))
                .param("discoveredUrls", Math.max(0, progress.discoveredUrlCount()))
                .param("visitedUrls", Math.max(0, progress.visitedUrlCount()))
                .param("collectedPages", Math.max(0, progress.collectedPageCount()))
                .param("currentUrl", truncateUrl(progress.currentUrl()))
                .param("heartbeatAt", timestamp(heartbeatAt))
                .param("leaseExpiresAt", timestamp(leaseExpiresAt))
                .update();
        int sourceUpdated = jdbc.sql("""
                update knowledge_source
                set locked_until=:leaseExpiresAt, updated_at=:heartbeatAt
                where id=:sourceId and status='RUNNING' and lock_token=:jobId
                  and locked_until >= :heartbeatAt
                """)
                .param("sourceId", source.sourceId())
                .param("jobId", source.jobId())
                .param("heartbeatAt", timestamp(heartbeatAt))
                .param("leaseExpiresAt", timestamp(leaseExpiresAt))
                .update();
        if (jobUpdated != 1 || sourceUpdated != 1) {
            throw new KnowledgeCollectionLeaseLostException(source.jobId());
        }
    }

    @Override
    @Transactional
    public SyncResult completeSuccessfulSync(SourceTask source, List<DocumentPage> pages,
                                             Instant completedAt, Instant nextSyncAt) {
        requireLeaseOwnership(source, completedAt);
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
        if (isExternalUpdateSource(source)) {
            for (DocumentPage page : pages) {
                upsertExternalUpdate(source, page, completedAt);
            }
        }
        SyncResult result = new SyncResult(pages.size(), newDocuments, changedDocuments,
                unchangedDocuments, chunkCount);
        int jobUpdated = jdbc.sql("""
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
        String fetchEtag = pages.isEmpty() ? null : pages.getFirst().etag();
        String fetchLastModified = pages.isEmpty() ? null : pages.getFirst().lastModified();
        int sourceUpdated = jdbc.sql("""
                update knowledge_source
                set status='SUCCEEDED', last_sync_at=:completedAt, next_sync_at=:nextSyncAt,
                    locked_until=null, lock_token=null, consecutive_failures=0,
                    last_error=null, fetch_etag=coalesce(:fetchEtag, fetch_etag),
                    fetch_last_modified=coalesce(:fetchLastModified, fetch_last_modified),
                    updated_at=:completedAt
                where id=:id and lock_token=:jobId
                """)
                .param("id", source.sourceId())
                .param("jobId", source.jobId())
                .param("completedAt", timestamp(completedAt))
                .param("nextSyncAt", timestamp(nextSyncAt))
                .param("fetchEtag", fetchEtag)
                .param("fetchLastModified", fetchLastModified)
                .update();
        jdbc.sql("""
                update knowledge_upload set status='SUCCEEDED', page_count=:pages,
                    error_message=null, updated_at=:now where source_id=:sourceId
                """).param("pages", result.pageCount()).param("now", timestamp(completedAt))
                .param("sourceId", source.sourceId()).update();
        if (jobUpdated != 1 || sourceUpdated != 1) {
            throw new KnowledgeCollectionLeaseLostException(source.jobId());
        }
        return result;
    }

    @Override
    @Transactional
    public void completeFailedSync(SourceTask source, String errorCode, String errorMessage,
                                   Instant failedAt, Instant nextRetryAt) {
        requireLeaseOwnership(source, failedAt);
        String message = truncate(errorMessage, 1000);
        int jobUpdated = jdbc.sql("""
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
        int sourceUpdated = jdbc.sql("""
                update knowledge_source
                set status='RETRY_WAIT', next_sync_at=:nextRetryAt, locked_until=null, lock_token=null,
                    consecutive_failures=consecutive_failures+1, last_error=:lastError,
                    updated_at=:failedAt
                where id=:id and lock_token=:jobId
                """)
                .param("id", source.sourceId())
                .param("jobId", source.jobId())
                .param("nextRetryAt", timestamp(nextRetryAt))
                .param("lastError", message)
                .param("failedAt", timestamp(failedAt))
                .update();
        jdbc.sql("""
                update knowledge_upload set status='FAILED', error_message=:error,
                    updated_at=:now where source_id=:sourceId
                """).param("error", message).param("now", timestamp(failedAt))
                .param("sourceId", source.sourceId()).update();
        if (jobUpdated != 1 || sourceUpdated != 1) {
            throw new KnowledgeCollectionLeaseLostException(source.jobId());
        }
    }

    @Override
    public List<SourceStatus> sourceStatus(UUID workspaceId) {
        return jdbc.sql("""
                select source.id, source.project_id, project.repository_name as project_name,
                       source.source_key, source.name, source.source_type, source.root_url,
                       source.discovery_url, source.allowed_host, source.allowed_path_prefix,
                       source.trust_tier, source.sync_interval_hours,
                       source.enabled, source.status, source.last_sync_at,
                       source.next_sync_at, source.consecutive_failures, source.last_error,
                       source.locked_until,
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
                       job.max_page_count, job.discovered_url_count, job.visited_url_count,
                       job.current_url, job.heartbeat_at, job.lease_expires_at,
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
                set next_sync_at=:now, locked_until=null, lock_token=null,
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

    @Override
    @Transactional
    public SourceStatus createSource(SourceDefinition source, Instant now) {
        jdbc.sql("""
                insert into knowledge_source
                    (id, workspace_id, project_id, source_key, name, source_type,
                     root_url, discovery_url, allowed_host, allowed_path_prefix,
                     trust_tier, sync_interval_hours, enabled, status, next_sync_at,
                     created_at, updated_at)
                values (:id, :workspaceId, :projectId, :sourceKey, :name, :sourceType,
                        :rootUrl, :discoveryUrl, :allowedHost, :allowedPathPrefix,
                        :trustTier, :syncIntervalHours, true, 'NEVER', :now, :now, :now)
                """)
                .param("id", source.sourceId())
                .param("workspaceId", source.workspaceId())
                .param("projectId", source.projectId())
                .param("sourceKey", source.sourceKey())
                .param("name", source.name())
                .param("sourceType", source.sourceType())
                .param("rootUrl", source.rootUrl())
                .param("discoveryUrl", source.discoveryUrl())
                .param("allowedHost", source.allowedHost())
                .param("allowedPathPrefix", source.allowedPathPrefix())
                .param("trustTier", source.trustTier())
                .param("syncIntervalHours", source.syncIntervalHours())
                .param("now", timestamp(now))
                .update();
        return findSourceStatus(source.workspaceId(), source.sourceId()).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<SourceStatus> updateSource(UUID workspaceId, UUID sourceId,
                                               SourceDefinition source, Instant now) {
        int updated = jdbc.sql("""
                update knowledge_source
                set project_id=:projectId, name=:name, source_type=:sourceType,
                    root_url=:rootUrl, discovery_url=:discoveryUrl,
                    allowed_host=:allowedHost, allowed_path_prefix=:allowedPathPrefix,
                    trust_tier=:trustTier, sync_interval_hours=:syncIntervalHours,
                    updated_at=:now
                where id=:sourceId and workspace_id=:workspaceId and status <> 'RUNNING'
                """)
                .param("projectId", source.projectId())
                .param("name", source.name())
                .param("sourceType", source.sourceType())
                .param("rootUrl", source.rootUrl())
                .param("discoveryUrl", source.discoveryUrl())
                .param("allowedHost", source.allowedHost())
                .param("allowedPathPrefix", source.allowedPathPrefix())
                .param("trustTier", source.trustTier())
                .param("syncIntervalHours", source.syncIntervalHours())
                .param("now", timestamp(now))
                .param("sourceId", sourceId)
                .param("workspaceId", workspaceId)
                .update();
        return updated == 1 ? findSourceStatus(workspaceId, sourceId) : Optional.empty();
    }

    @Override
    @Transactional
    public Optional<SourceStatus> setSourceEnabled(UUID workspaceId, UUID sourceId,
                                                   boolean enabled, Instant now) {
        int updated = jdbc.sql("""
                update knowledge_source
                set enabled=:enabled,
                    next_sync_at=case when :enabled then :now else next_sync_at end,
                    status=case when :enabled and last_sync_at is null then 'RETRY_WAIT'
                                else status end,
                    locked_until=case when :enabled then locked_until else null end,
                    lock_token=case when :enabled then lock_token else null end,
                    updated_at=:now
                where id=:sourceId and workspace_id=:workspaceId and status <> 'RUNNING'
                """)
                .param("enabled", enabled)
                .param("now", timestamp(now))
                .param("sourceId", sourceId)
                .param("workspaceId", workspaceId)
                .update();
        return updated == 1 ? findSourceStatus(workspaceId, sourceId) : Optional.empty();
    }

    @Override
    @Transactional
    public DeleteResult deleteEmptySource(UUID workspaceId, UUID sourceId) {
        Optional<UUID> locked = jdbc.sql("""
                select id from knowledge_source
                where id=:sourceId and workspace_id=:workspaceId
                for update
                """)
                .param("sourceId", sourceId)
                .param("workspaceId", workspaceId)
                .query(UUID.class)
                .optional();
        if (locked.isEmpty()) return DeleteResult.NOT_FOUND;
        boolean dependent = jdbc.sql("""
                select exists(select 1 from knowledge_document where source_id=:sourceId)
                    or exists(select 1 from knowledge_collection_job
                              where source_id=:sourceId and status='RUNNING')
                """)
                .param("sourceId", sourceId)
                .query(Boolean.class)
                .single();
        if (dependent) return DeleteResult.HAS_DEPENDENCIES;
        jdbc.sql("delete from knowledge_source where id=:sourceId")
                .param("sourceId", sourceId)
                .update();
        return DeleteResult.DELETED;
    }

    private Optional<SourceStatus> findSourceStatus(UUID workspaceId, UUID sourceId) {
        return sourceStatus(workspaceId).stream()
                .filter(source -> source.sourceId().equals(sourceId))
                .findFirst();
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
                rs.getString("trust_tier"), rs.getInt("sync_interval_hours"),
                rs.getInt("consecutive_failures"), rs.getString("fetch_etag"),
                rs.getString("fetch_last_modified"), rs.getString("upload_storage_key"),
                rs.getString("upload_original_name"), rs.getString("upload_media_type"),
                rs.getString("upload_visibility"), rs.getObject("upload_user_id", UUID.class));
    }

    private static boolean isExternalUpdateSource(SourceTask source) {
        return "OFFICIAL_BLOG_RSS".equals(source.sourceType())
                || "OFFICIAL_ROADMAP".equals(source.sourceType());
    }

    private void upsertExternalUpdate(SourceTask source, DocumentPage page, Instant collectedAt) {
        String snapshotType = "OFFICIAL_BLOG_RSS".equals(source.sourceType()) ? "RSS" : "ROADMAP";
        String eventType = "OFFICIAL_BLOG_RSS".equals(source.sourceType())
                ? "OFFICIAL_BLOG" : "ROADMAP";
        Instant occurredAt = externalOccurredAt(page.versionLabel(), collectedAt);
        String externalId = UUID.nameUUIDFromBytes(page.canonicalUrl()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String raw;
        try {
            raw = json.writeValueAsString(Map.of(
                    "title", page.title(), "url", page.canonicalUrl(),
                    "content", page.contentText(), "sourceKey", source.sourceKey()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize external knowledge update", exception);
        }
        UUID snapshotId = jdbc.sql("""
                insert into source_snapshot
                    (id, project_id, source_type, external_id, version_tag, source_url,
                     content_sha256, raw_content, published_at, collected_at)
                values (gen_random_uuid(), :projectId, :sourceType, :externalId, :version,
                        :url, :hash, cast(:raw as jsonb), :occurredAt, :collectedAt)
                on conflict (project_id, source_type, external_id) do update set
                    version_tag=excluded.version_tag, source_url=excluded.source_url,
                    content_sha256=excluded.content_sha256, raw_content=excluded.raw_content,
                    published_at=coalesce(source_snapshot.published_at, excluded.published_at),
                    collected_at=excluded.collected_at
                returning id
                """).param("projectId", source.projectId()).param("sourceType", snapshotType)
                .param("externalId", externalId).param("version", page.versionLabel())
                .param("url", page.canonicalUrl()).param("hash", page.contentSha256())
                .param("raw", raw).param("occurredAt", timestamp(occurredAt))
                .param("collectedAt", timestamp(collectedAt)).query(UUID.class).single();
        String summary = truncate(page.contentText().replaceAll("\\s+", " "), 1000);
        UUID eventId = jdbc.sql("""
                insert into intelligence_event
                    (id, project_id, snapshot_id, event_type, title, summary,
                     importance, occurred_at, payload, updated_at)
                values (gen_random_uuid(), :projectId, :snapshotId, :eventType, :title,
                        :summary, 3, :occurredAt, cast(:payload as jsonb), :updatedAt)
                on conflict (snapshot_id) do update set
                    event_type=excluded.event_type, title=excluded.title,
                    summary=excluded.summary,
                    payload=excluded.payload, updated_at=excluded.updated_at
                returning id
                """).param("projectId", source.projectId()).param("snapshotId", snapshotId)
                .param("eventType", eventType).param("title", truncate(page.title(), 512))
                .param("summary", summary).param("occurredAt", timestamp(occurredAt))
                .param("payload", raw).param("updatedAt", timestamp(collectedAt))
                .query(UUID.class).single();
        jdbc.sql("""
                insert into event_evidence
                    (id, event_id, snapshot_id, source_url, evidence_text, sort_order, created_at)
                values (gen_random_uuid(), :eventId, :snapshotId, :url, :evidence, 0, :now)
                on conflict (event_id, snapshot_id, sort_order) do update set
                    source_url=excluded.source_url, evidence_text=excluded.evidence_text
                """).param("eventId", eventId).param("snapshotId", snapshotId)
                .param("url", page.canonicalUrl()).param("evidence", summary)
                .param("now", timestamp(collectedAt)).update();
        applyWatchRules(eventId, collectedAt);
    }

    private void applyWatchRules(UUID eventId, Instant matchedAt) {
        jdbc.sql("""
                insert into event_rule_match (rule_id, event_id, user_id, workspace_id, matched_at)
                select rule.id, event.id, rule.user_id, rule.workspace_id, :matchedAt
                from intelligence_event event
                join tracked_project project on project.id=event.project_id
                join user_watch_rule rule on rule.workspace_id=project.workspace_id and rule.enabled=true
                where event.id=:eventId
                  and (rule.project_id is null or rule.project_id=event.project_id)
                  and event.importance >= rule.minimum_importance
                  and (cardinality(rule.event_types)=0 or event.event_type=any(rule.event_types))
                  and (cardinality(rule.keywords)=0 or exists (
                      select 1 from unnest(rule.keywords) keyword
                      where lower(event.title || ' ' || event.summary || ' ' || array_to_string(event.labels, ' '))
                            like '%' || lower(keyword) || '%'))
                  and not exists (select 1 from unnest(rule.excluded_keywords) keyword
                      where lower(event.title || ' ' || event.summary || ' ' || array_to_string(event.labels, ' '))
                            like '%' || lower(keyword) || '%')
                on conflict (rule_id, event_id) do nothing
                """).param("eventId", eventId).param("matchedAt", timestamp(matchedAt)).update();
        jdbc.sql("""
                insert into user_notification
                    (id, user_id, workspace_id, notification_type, entity_id,
                     severity, title, body, created_at)
                select gen_random_uuid(), match.user_id, match.workspace_id, 'RULE_MATCH', event.id,
                       'INFO', '关注规则命中：' || left(event.title, 230),
                       left(project.repository_owner || '/' || project.repository_name || ' · '
                            || event.event_type || ' · ' || event.summary, 1000), :createdAt
                from event_rule_match match
                join user_watch_rule rule on rule.id=match.rule_id and rule.immediate_notification=true
                join intelligence_event event on event.id=match.event_id
                join tracked_project project on project.id=event.project_id
                where match.event_id=:eventId
                on conflict (user_id, notification_type, entity_id) do nothing
                """).param("eventId", eventId).param("createdAt", timestamp(matchedAt)).update();
        jdbc.sql("""
                update intelligence_event event set analysis_eligible=true
                where event.id=:eventId and exists (
                    select 1 from event_rule_match match
                    join user_watch_rule rule on rule.id=match.rule_id
                    where match.event_id=event.id and rule.include_in_digest=true)
                """).param("eventId", eventId).update();
    }

    private SourceStatus sourceStatus(ResultSet rs, int rowNum) throws SQLException {
        UUID jobId = rs.getObject("job_id", UUID.class);
        JobStatus job = jobId == null ? null : new JobStatus(jobId, rs.getString("job_status"),
                rs.getInt("page_count"), rs.getInt("new_document_count"),
                rs.getInt("changed_document_count"), rs.getInt("unchanged_document_count"),
                rs.getInt("job_chunk_count"), rs.getInt("max_page_count"),
                rs.getInt("discovered_url_count"), rs.getInt("visited_url_count"),
                rs.getString("current_url"), instant(rs, "heartbeat_at"),
                instant(rs, "lease_expires_at"), rs.getString("error_code"),
                rs.getString("error_message"), instant(rs, "started_at"), instant(rs, "finished_at"));
        return new SourceStatus(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_name"), rs.getString("source_key"), rs.getString("name"),
                rs.getString("source_type"), rs.getString("root_url"), rs.getString("discovery_url"),
                rs.getString("allowed_host"), rs.getString("allowed_path_prefix"),
                rs.getString("trust_tier"), rs.getInt("sync_interval_hours"),
                rs.getBoolean("enabled"), rs.getString("status"), instant(rs, "last_sync_at"),
                instant(rs, "next_sync_at"), rs.getInt("consecutive_failures"),
                rs.getString("last_error"), rs.getLong("document_count"),
                rs.getLong("revision_count"), rs.getLong("chunk_count"),
                instant(rs, "locked_until"), job);
    }

    private void requireLeaseOwnership(SourceTask source, Instant now) {
        boolean owned = jdbc.sql("""
                select exists (
                    select 1 from knowledge_source
                    where id=:sourceId and status='RUNNING' and lock_token=:jobId
                      and locked_until >= :now
                    for update
                )
                """)
                .param("sourceId", source.sourceId())
                .param("jobId", source.jobId())
                .param("now", timestamp(now))
                .query(Boolean.class)
                .single();
        if (!owned) throw new KnowledgeCollectionLeaseLostException(source.jobId());
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

    private static String truncateUrl(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private static Instant externalOccurredAt(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ignored) {
            try {
                return java.time.ZonedDateTime.parse(
                        value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (java.time.format.DateTimeParseException alsoIgnored) {
                return fallback;
            }
        }
    }

    private record DocumentState(UUID id, UUID currentRevisionId, String contentSha256) { }
    private record ChunkState(String contentSha256, String pipelineVersion) { }
}
