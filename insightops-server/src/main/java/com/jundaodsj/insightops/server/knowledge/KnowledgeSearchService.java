package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.knowledge.application.TextEmbeddingGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeSearchService {
    private final KnowledgeEmbeddingStore store;
    private final TextEmbeddingGateway gateway;
    private final KnowledgeEmbeddingProperties properties;
    private final HybridSearchRanker ranker;
    private final Clock clock;

    @Autowired
    public KnowledgeSearchService(KnowledgeEmbeddingStore store, TextEmbeddingGateway gateway,
                                  KnowledgeEmbeddingProperties properties,
                                  HybridSearchRanker ranker) {
        this(store, gateway, properties, ranker, Clock.systemUTC());
    }

    KnowledgeSearchService(KnowledgeEmbeddingStore store, TextEmbeddingGateway gateway,
                           KnowledgeEmbeddingProperties properties, HybridSearchRanker ranker,
                           Clock clock) {
        this.store = store;
        this.gateway = gateway;
        this.properties = properties;
        this.ranker = ranker;
        this.clock = clock;
    }

    public SearchResponse search(UUID workspaceId, String query, int limit) {
        return searchVisible(null, workspaceId, null, true, query, limit);
    }

    public SearchResponse search(UUID runId, UUID workspaceId, String query, int limit) {
        return searchVisible(runId, workspaceId, null, true, query, limit);
    }

    public SearchResponse searchForUser(UUID runId, UUID workspaceId, UUID viewerUserId,
                                        boolean systemAdmin, String query, int limit) {
        return searchVisible(runId, workspaceId, viewerUserId, systemAdmin, query, limit);
    }

    private SearchResponse searchVisible(UUID runId, UUID workspaceId, UUID viewerUserId,
                                         boolean systemAdmin, String query, int limit) {
        Instant startedAt = clock.instant();
        List<KnowledgeEmbeddingStore.SearchResult> vectorResults = List.of();
        boolean vectorAvailable = properties.isEnabled();
        if (vectorAvailable) {
            try {
                List<float[]> vectors = gateway.embed(List.of(query));
                if (vectors.size() != 1 || vectors.getFirst() == null
                        || vectors.getFirst().length != properties.getDimensions()) {
                    throw new IllegalStateException("Embedding model returned an invalid vector");
                }
                int candidateLimit = Math.max(1, Math.min(50, limit * 2));
                vectorResults = systemAdmin
                        ? store.search(workspaceId, properties.getModel(), vectors.getFirst(),
                                candidateLimit, properties.getMinimumScore())
                        : store.searchVisible(workspaceId, viewerUserId, false, properties.getModel(),
                                vectors.getFirst(), candidateLimit, properties.getMinimumScore());
            }
            catch (RuntimeException exception) {
                vectorAvailable = false;
            }
        }
        List<KnowledgeEmbeddingStore.SearchResult> keywordResults;
        try {
            int candidateLimit = Math.max(1, Math.min(50, limit * 2));
            keywordResults = systemAdmin
                    ? store.searchKeyword(workspaceId, query, candidateLimit)
                    : store.searchKeywordVisible(workspaceId, viewerUserId, false, query, candidateLimit);
        }
        catch (RuntimeException exception) {
            if (!vectorAvailable) {
                throw new EmbeddingUnavailableException("Hybrid retrieval is temporarily unavailable");
            }
            keywordResults = List.of();
        }
        List<KnowledgeEmbeddingStore.SearchResult> results = ranker.fuse(
                query, vectorResults, keywordResults, limit);
        long durationMs = Math.max(0, java.time.Duration.between(startedAt, clock.instant()).toMillis());
        String mode = vectorAvailable && !keywordResults.isEmpty() ? "HYBRID"
                : vectorAvailable ? "VECTOR" : "KEYWORD";
        store.recordRetrieval(runId, workspaceId, query, mode, results.size(), durationMs,
                results, clock.instant());
        String provider = "HYBRID".equals(mode) ? properties.getProvider() + "+postgresql"
                : "VECTOR".equals(mode) ? properties.getProvider() : "postgresql";
        String model = "HYBRID".equals(mode) ? properties.getModel() + "+fts"
                : "VECTOR".equals(mode) ? properties.getModel() : "fts";
        return new SearchResponse(query, provider, model, mode, vectorAvailable,
                durationMs, results);
    }

    public record SearchResponse(String query, String provider, String model, String mode,
                                 boolean vectorAvailable, long durationMs,
                                 List<KnowledgeEmbeddingStore.SearchResult> results) {
        public SearchResponse(String query, String provider, String model, long durationMs,
                              List<KnowledgeEmbeddingStore.SearchResult> results) {
            this(query, provider, model, "VECTOR", true, durationMs, results);
        }
    }

    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message) { super(message); }
    }
}
