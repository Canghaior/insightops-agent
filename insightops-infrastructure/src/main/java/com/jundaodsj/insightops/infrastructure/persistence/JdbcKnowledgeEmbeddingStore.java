package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcKnowledgeEmbeddingStore implements KnowledgeEmbeddingStore {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcKnowledgeEmbeddingStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public int prepareCurrentChunks(String provider, String model, int dimensions, Instant now) {
        jdbc.sql("""
                update knowledge_embedding
                set status='RETRY_WAIT', locked_until=null, next_attempt_at=:now,
                    last_error='Previous embedding worker lease expired', updated_at=:now
                where embedding_model=:model and status='RUNNING' and locked_until < :now
                """)
                .param("model", model)
                .param("now", timestamp(now))
                .update();
        return jdbc.sql("""
                insert into knowledge_embedding
                    (chunk_id, provider, embedding_model, dimensions, status,
                     next_attempt_at, created_at, updated_at)
                select chunk.id, :provider, :model, :dimensions, 'PENDING', :now, :now, :now
                from knowledge_chunk chunk
                join knowledge_document document on document.current_revision_id=chunk.revision_id
                join knowledge_source source on source.id=document.source_id
                where document.active=true and source.enabled=true
                on conflict (chunk_id, embedding_model) do nothing
                """)
                .param("provider", provider)
                .param("model", model)
                .param("dimensions", dimensions)
                .param("now", timestamp(now))
                .update();
    }

    @Override
    @Transactional
    public List<EmbeddingTask> claimPending(String model, Instant now, Duration lockDuration, int limit) {
        List<EmbeddingTask> tasks = jdbc.sql("""
                select embedding.chunk_id, chunk.content, embedding.attempts
                from knowledge_embedding embedding
                join knowledge_chunk chunk on chunk.id=embedding.chunk_id
                join knowledge_document document on document.current_revision_id=chunk.revision_id
                join knowledge_source source on source.id=document.source_id
                where embedding.embedding_model=:model
                  and document.active=true and source.enabled=true
                  and embedding.status in ('PENDING', 'RETRY_WAIT')
                  and embedding.next_attempt_at <= :now
                  and (embedding.locked_until is null or embedding.locked_until < :now)
                order by embedding.next_attempt_at, chunk.id
                for update of embedding skip locked
                limit :limit
                """)
                .param("model", model)
                .param("now", timestamp(now))
                .param("limit", Math.max(1, limit))
                .query((rs, rowNum) -> new EmbeddingTask(
                        rs.getObject("chunk_id", UUID.class), rs.getString("content"),
                        rs.getInt("attempts")))
                .list();
        for (EmbeddingTask task : tasks) {
            jdbc.sql("""
                    update knowledge_embedding
                    set status='RUNNING', attempts=attempts+1, locked_until=:lockedUntil,
                        last_error=null, updated_at=:now
                    where chunk_id=:chunkId and embedding_model=:model
                    """)
                    .param("chunkId", task.chunkId())
                    .param("model", model)
                    .param("lockedUntil", timestamp(now.plus(lockDuration)))
                    .param("now", timestamp(now))
                    .update();
        }
        return List.copyOf(tasks);
    }

    @Override
    public void complete(UUID chunkId, String model, float[] embedding, Instant completedAt) {
        jdbc.sql("""
                update knowledge_embedding
                set status='SUCCEEDED', embedding=cast(:embedding as public.vector), locked_until=null,
                    last_error=null, updated_at=:now
                where chunk_id=:chunkId and embedding_model=:model and status='RUNNING'
                """)
                .param("chunkId", chunkId)
                .param("model", model)
                .param("embedding", vector(embedding))
                .param("now", timestamp(completedAt))
                .update();
    }

    @Override
    public void fail(UUID chunkId, String model, String errorMessage, Instant failedAt,
                     Instant nextAttemptAt, int maxAttempts) {
        jdbc.sql("""
                update knowledge_embedding
                set status=case when attempts >= :maxAttempts then 'FAILED' else 'RETRY_WAIT' end,
                    embedding=null, locked_until=null, next_attempt_at=:nextAttemptAt,
                    last_error=:lastError, updated_at=:failedAt
                where chunk_id=:chunkId and embedding_model=:model and status='RUNNING'
                """)
                .param("chunkId", chunkId)
                .param("model", model)
                .param("maxAttempts", Math.max(1, maxAttempts))
                .param("nextAttemptAt", timestamp(nextAttemptAt))
                .param("lastError", truncate(errorMessage, 1000))
                .param("failedAt", timestamp(failedAt))
                .update();
    }

    @Override
    public EmbeddingOverview overview(UUID workspaceId, String model) {
        Overview totals = jdbc.sql("""
                select coalesce(max(embedding.provider), '') as provider,
                       coalesce(max(embedding.dimensions), 0) as dimensions,
                       count(*) as total,
                       count(*) filter (where embedding.status='SUCCEEDED') as succeeded,
                       count(*) filter (where embedding.status is null or embedding.status='PENDING') as pending,
                       count(*) filter (where embedding.status='RUNNING') as running,
                       count(*) filter (where embedding.status='RETRY_WAIT') as retry_wait,
                       count(*) filter (where embedding.status='FAILED') as failed,
                       max(embedding.updated_at) as last_updated_at
                from knowledge_chunk chunk
                join knowledge_document document on document.current_revision_id=chunk.revision_id and document.active=true
                join knowledge_source source on source.id=document.source_id and source.enabled=true
                left join knowledge_embedding embedding
                  on embedding.chunk_id=chunk.id and embedding.embedding_model=:model
                where source.workspace_id=:workspaceId
                """)
                .param("workspaceId", workspaceId)
                .param("model", model)
                .query((rs, rowNum) -> overview(rs))
                .single();
        List<SourceProgress> sources = jdbc.sql("""
                select source.id, source.name, project.repository_name as project_name,
                       count(*) as total,
                       count(*) filter (where embedding.status='SUCCEEDED') as succeeded,
                       count(*) filter (where embedding.status is null or embedding.status='PENDING') as pending,
                       count(*) filter (where embedding.status='RUNNING') as running,
                       count(*) filter (where embedding.status='RETRY_WAIT') as retry_wait,
                       count(*) filter (where embedding.status='FAILED') as failed
                from knowledge_chunk chunk
                join knowledge_document document on document.current_revision_id=chunk.revision_id and document.active=true
                join knowledge_source source on source.id=document.source_id and source.enabled=true
                join tracked_project project on project.id=source.project_id
                left join knowledge_embedding embedding
                  on embedding.chunk_id=chunk.id and embedding.embedding_model=:model
                where source.workspace_id=:workspaceId
                group by source.id, source.name, project.repository_name, project.priority
                order by project.priority, source.name
                """)
                .param("workspaceId", workspaceId)
                .param("model", model)
                .query((rs, rowNum) -> new SourceProgress(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("project_name"), rs.getLong("total"),
                        rs.getLong("succeeded"), rs.getLong("pending"),
                        rs.getLong("running"), rs.getLong("retry_wait"), rs.getLong("failed")))
                .list();
        return new EmbeddingOverview(totals.provider(), model, totals.dimensions(), totals.total(),
                totals.succeeded(), totals.pending(), totals.running(), totals.retryWait(),
                totals.failed(), totals.lastUpdatedAt(), sources);
    }

    @Override
    public int retryFailed(UUID workspaceId, String model, Instant now) {
        return jdbc.sql("""
                update knowledge_embedding embedding
                set status='PENDING', attempts=0, next_attempt_at=:now, locked_until=null,
                    last_error=null, updated_at=:now
                from knowledge_chunk chunk
                join knowledge_document document on document.current_revision_id=chunk.revision_id
                join knowledge_source source on source.id=document.source_id
                where embedding.chunk_id=chunk.id and embedding.embedding_model=:model
                  and source.workspace_id=:workspaceId and document.active=true
                  and embedding.status='FAILED'
                """)
                .param("workspaceId", workspaceId)
                .param("model", model)
                .param("now", timestamp(now))
                .update();
    }

    @Override
    public List<SearchResult> search(UUID workspaceId, String model, float[] queryEmbedding,
                                     int limit, double minimumScore) {
        String query = vector(queryEmbedding);
        return jdbc.sql("""
                select chunk.id as chunk_id, source.project_id, project.repository_name as project_name,
                       source.name as source_name, document.title, document.canonical_url,
                       chunk.heading_path, chunk.content, document.language, source.trust_tier,
                       1 - (embedding.embedding OPERATOR(public.<=>) cast(:query as public.vector)) as score
                from knowledge_embedding embedding
                join knowledge_chunk chunk on chunk.id=embedding.chunk_id
                join knowledge_document document on document.current_revision_id=chunk.revision_id and document.active=true
                join knowledge_source source on source.id=document.source_id and source.enabled=true
                join tracked_project project on project.id=source.project_id
                where source.workspace_id=:workspaceId and embedding.embedding_model=:model
                  and embedding.status='SUCCEEDED'
                  and 1 - (embedding.embedding OPERATOR(public.<=>) cast(:query as public.vector)) >= :minimumScore
                order by embedding.embedding OPERATOR(public.<=>) cast(:query as public.vector)
                limit :limit
                """)
                .param("workspaceId", workspaceId)
                .param("model", model)
                .param("query", query)
                .param("minimumScore", Math.max(-1.0, Math.min(1.0, minimumScore)))
                .param("limit", Math.max(1, Math.min(20, limit)))
                .query((rs, rowNum) -> new SearchResult(
                        rs.getObject("chunk_id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getString("project_name"), rs.getString("source_name"),
                        rs.getString("title"), rs.getString("canonical_url"),
                        rs.getString("heading_path"), rs.getString("content"),
                        rs.getString("language"), rs.getString("trust_tier"), rs.getDouble("score")))
                .list();
    }

    @Override
    public void recordRetrieval(UUID workspaceId, String query, String mode, int resultCount,
                                long durationMs, List<SearchResult> results, Instant createdAt) {
        jdbc.sql("""
                insert into retrieval_trace
                    (id, workspace_id, query_text, retrieval_mode, result_summary,
                     result_count, duration_ms, created_at)
                values (:id, :workspaceId, :query, :mode, cast(:summary as jsonb),
                        :resultCount, :durationMs, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("query", query)
                .param("mode", mode)
                .param("summary", summary(results))
                .param("resultCount", resultCount)
                .param("durationMs", durationMs)
                .param("createdAt", timestamp(createdAt))
                .update();
    }

    private static Overview overview(ResultSet rs) throws SQLException {
        return new Overview(rs.getString("provider"), rs.getInt("dimensions"), rs.getLong("total"),
                rs.getLong("succeeded"), rs.getLong("pending"), rs.getLong("running"),
                rs.getLong("retry_wait"), rs.getLong("failed"), instant(rs, "last_updated_at"));
    }

    private String summary(List<SearchResult> results) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (SearchResult result : results) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("chunkId", result.chunkId());
            value.put("projectId", result.projectId());
            value.put("canonicalUrl", result.canonicalUrl());
            value.put("score", result.score());
            values.add(value);
        }
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize retrieval trace", exception);
        }
    }

    private static String vector(float[] values) {
        StringBuilder result = new StringBuilder(values.length * 10).append('[');
        for (int index = 0; index < values.length; index++) {
            float value = values[index];
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding contains a non-finite value");
            if (index > 0) result.append(',');
            result.append(value);
        }
        return result.append(']').toString();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        String text = value == null || value.isBlank() ? "Unknown embedding failure" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private record Overview(String provider, int dimensions, long total, long succeeded,
                            long pending, long running, long retryWait, long failed,
                            Instant lastUpdatedAt) {
    }
}
