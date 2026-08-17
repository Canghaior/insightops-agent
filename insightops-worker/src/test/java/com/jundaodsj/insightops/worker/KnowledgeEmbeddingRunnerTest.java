package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.knowledge.application.TextEmbeddingGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeEmbeddingRunnerTest {

    @Test
    void embedsAClaimedBatchAndValidatesDimensions() {
        KnowledgeEmbeddingStore store = mock(KnowledgeEmbeddingStore.class);
        TextEmbeddingGateway gateway = mock(TextEmbeddingGateway.class);
        KnowledgeEmbeddingProperties properties = properties();
        UUID chunkId = UUID.randomUUID();
        var task = new KnowledgeEmbeddingStore.EmbeddingTask(chunkId, "Spring AI Ollama embedding", 0);
        when(store.prepareCurrentChunks(any(), any(), anyInt(), any())).thenReturn(1);
        when(store.claimPending(eq("bge-m3"), any(), any(), anyInt()))
                .thenReturn(List.of(task), List.of());
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        when(gateway.embed(anyList())).thenReturn(List.of(vector));

        var result = new KnowledgeEmbeddingRunner(store, gateway, properties,
                Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC))
                .embedPendingChunks();

        assertThat(result.prepared()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(store).complete(eq(chunkId), eq("bge-m3"), eq(vector), any());
    }

    @Test
    void schedulesRetryWhenProviderFails() {
        KnowledgeEmbeddingStore store = mock(KnowledgeEmbeddingStore.class);
        TextEmbeddingGateway gateway = mock(TextEmbeddingGateway.class);
        KnowledgeEmbeddingProperties properties = properties();
        UUID chunkId = UUID.randomUUID();
        when(store.claimPending(eq("bge-m3"), any(), any(), anyInt()))
                .thenReturn(List.of(new KnowledgeEmbeddingStore.EmbeddingTask(chunkId, "content", 0)), List.of());
        when(gateway.embed(anyList())).thenThrow(new IllegalStateException("ollama offline"));

        var result = new KnowledgeEmbeddingRunner(store, gateway, properties,
                Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC))
                .embedPendingChunks();

        assertThat(result.failed()).isEqualTo(1);
        verify(store).fail(eq(chunkId), eq("bge-m3"),
                org.mockito.ArgumentMatchers.contains("ollama offline"), any(), any(), eq(3));
    }

    private static KnowledgeEmbeddingProperties properties() {
        var properties = new KnowledgeEmbeddingProperties();
        properties.setEnabled(true);
        properties.setMaxBatchesPerCycle(2);
        return properties;
    }
}
