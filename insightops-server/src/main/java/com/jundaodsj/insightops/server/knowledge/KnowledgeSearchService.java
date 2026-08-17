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
    private final Clock clock;

    @Autowired
    public KnowledgeSearchService(KnowledgeEmbeddingStore store, TextEmbeddingGateway gateway,
                                  KnowledgeEmbeddingProperties properties) {
        this(store, gateway, properties, Clock.systemUTC());
    }

    KnowledgeSearchService(KnowledgeEmbeddingStore store, TextEmbeddingGateway gateway,
                           KnowledgeEmbeddingProperties properties, Clock clock) {
        this.store = store;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    public SearchResponse search(UUID workspaceId, String query, int limit) {
        if (!properties.isEnabled()) {
            throw new EmbeddingUnavailableException("Semantic retrieval is not enabled");
        }
        Instant startedAt = clock.instant();
        List<float[]> vectors;
        try {
            vectors = gateway.embed(List.of(query));
        } catch (RuntimeException exception) {
            throw new EmbeddingUnavailableException("Embedding service is temporarily unavailable");
        }
        if (vectors.size() != 1 || vectors.getFirst() == null
                || vectors.getFirst().length != properties.getDimensions()) {
            throw new EmbeddingUnavailableException("Embedding model returned an invalid vector");
        }
        var results = store.search(workspaceId, properties.getModel(), vectors.getFirst(),
                Math.max(1, Math.min(20, limit)), properties.getMinimumScore());
        long durationMs = Math.max(0, java.time.Duration.between(startedAt, clock.instant()).toMillis());
        store.recordRetrieval(workspaceId, query, "VECTOR", results.size(), durationMs,
                results, clock.instant());
        return new SearchResponse(query, properties.getProvider(), properties.getModel(),
                durationMs, results);
    }

    public record SearchResponse(String query, String provider, String model, long durationMs,
                                 List<KnowledgeEmbeddingStore.SearchResult> results) {
    }

    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message) { super(message); }
    }
}
