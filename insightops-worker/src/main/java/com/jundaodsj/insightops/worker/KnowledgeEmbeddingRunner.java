package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.knowledge.application.TextEmbeddingGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class KnowledgeEmbeddingRunner {
    private final KnowledgeEmbeddingStore store;
    private final TextEmbeddingGateway gateway;
    private final KnowledgeEmbeddingProperties properties;
    private final Clock clock;

    @Autowired
    public KnowledgeEmbeddingRunner(KnowledgeEmbeddingStore store, TextEmbeddingGateway gateway,
                                    KnowledgeEmbeddingProperties properties) {
        this(store, gateway, properties, Clock.systemUTC());
    }

    KnowledgeEmbeddingRunner(KnowledgeEmbeddingStore store, TextEmbeddingGateway gateway,
                             KnowledgeEmbeddingProperties properties, Clock clock) {
        this.store = store;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    public CycleResult embedPendingChunks() {
        Instant startedAt = clock.instant();
        int prepared = store.prepareCurrentChunks(properties.getProvider(), properties.getModel(),
                properties.getDimensions(), startedAt);
        int claimed = 0;
        int succeeded = 0;
        int failed = 0;
        int batches = 0;
        for (int batch = 0; batch < Math.max(1, properties.getMaxBatchesPerCycle()); batch++) {
            List<KnowledgeEmbeddingStore.EmbeddingTask> tasks = store.claimPending(
                    properties.getModel(), clock.instant(),
                    Duration.ofMinutes(Math.max(1, properties.getLockMinutes())),
                    Math.max(1, properties.getBatchSize()));
            if (tasks.isEmpty()) break;
            batches++;
            claimed += tasks.size();
            try {
                List<float[]> vectors = gateway.embed(tasks.stream()
                        .map(KnowledgeEmbeddingStore.EmbeddingTask::content).toList());
                validate(vectors, tasks.size(), properties.getDimensions());
                Instant completedAt = clock.instant();
                for (int index = 0; index < tasks.size(); index++) {
                    store.complete(tasks.get(index).chunkId(), properties.getModel(),
                            vectors.get(index), completedAt);
                    succeeded++;
                }
            } catch (RuntimeException exception) {
                failed += tasks.size();
                Instant failedAt = clock.instant();
                Instant nextAttemptAt = failedAt.plus(Duration.ofMinutes(
                        Math.max(1, properties.getRetryMinutes())));
                String error = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
                for (var task : tasks) {
                    store.fail(task.chunkId(), properties.getModel(), error, failedAt,
                            nextAttemptAt, Math.max(1, properties.getMaxRetries()));
                }
            }
        }
        return new CycleResult(prepared, batches, claimed, succeeded, failed, startedAt, clock.instant());
    }

    private static void validate(List<float[]> vectors, int expectedCount, int dimensions) {
        if (vectors == null || vectors.size() != expectedCount) {
            throw new IllegalStateException("Embedding response count does not match request count");
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != dimensions) {
                throw new IllegalStateException("Embedding dimensions do not match configured dimensions");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("Embedding contains a non-finite value");
                }
            }
        }
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "Embedding request failed" : exception.getMessage();
    }

    public record CycleResult(int prepared, int batches, int claimed, int succeeded, int failed,
                              Instant startedAt, Instant finishedAt) {
    }
}
