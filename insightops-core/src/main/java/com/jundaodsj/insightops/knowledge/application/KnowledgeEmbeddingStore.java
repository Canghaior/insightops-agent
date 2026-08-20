package com.jundaodsj.insightops.knowledge.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface KnowledgeEmbeddingStore {

    int prepareCurrentChunks(String provider, String model, int dimensions, Instant now);

    List<EmbeddingTask> claimPending(String model, Instant now, Duration lockDuration, int limit);

    void complete(UUID chunkId, String model, float[] embedding, Instant completedAt);

    void fail(UUID chunkId, String model, String errorMessage, Instant failedAt,
              Instant nextAttemptAt, int maxAttempts);

    EmbeddingOverview overview(UUID workspaceId, String model);

    int retryFailed(UUID workspaceId, String model, Instant now);

    List<SearchResult> search(UUID workspaceId, String model, float[] queryEmbedding,
                              int limit, double minimumScore);

    List<SearchResult> searchKeyword(UUID workspaceId, String query, int limit);

    default List<SearchResult> searchVisible(UUID workspaceId, UUID viewerUserId, boolean systemAdmin,
                                             String model, float[] queryEmbedding,
                                             int limit, double minimumScore) {
        return search(workspaceId, model, queryEmbedding, limit, minimumScore);
    }

    default List<SearchResult> searchKeywordVisible(UUID workspaceId, UUID viewerUserId,
                                                    boolean systemAdmin, String query, int limit) {
        return searchKeyword(workspaceId, query, limit);
    }

    void recordRetrieval(UUID runId, UUID workspaceId, String query, String mode, int resultCount,
                         long durationMs, List<SearchResult> results, Instant createdAt);

    default void recordRetrieval(UUID workspaceId, String query, String mode, int resultCount,
                                 long durationMs, List<SearchResult> results, Instant createdAt) {
        recordRetrieval(null, workspaceId, query, mode, resultCount,
                durationMs, results, createdAt);
    }

    record EmbeddingTask(UUID chunkId, String content, int attempts) {
    }

    record EmbeddingOverview(String provider, String model, int dimensions, long total,
                             long succeeded, long pending, long running, long retryWait,
                             long failed, Instant lastUpdatedAt, List<SourceProgress> sources) {
    }

    record SourceProgress(UUID sourceId, String sourceName, String projectName, long total,
                          long succeeded, long pending, long running, long retryWait, long failed) {
    }

    record SearchResult(UUID chunkId, UUID projectId, String projectName, String sourceName,
                        String title, String canonicalUrl, String headingPath, String content,
                        String language, String trustTier, double score) {
    }
}
