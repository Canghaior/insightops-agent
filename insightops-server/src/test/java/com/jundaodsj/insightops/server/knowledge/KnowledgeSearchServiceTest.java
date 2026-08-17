package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.knowledge.application.TextEmbeddingGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchServiceTest {

    @Test
    void embedsSearchesAndAuditsTheQuery() {
        KnowledgeEmbeddingStore store = mock(KnowledgeEmbeddingStore.class);
        TextEmbeddingGateway gateway = mock(TextEmbeddingGateway.class);
        KnowledgeEmbeddingProperties properties = properties(true);
        UUID workspaceId = UUID.randomUUID();
        float[] vector = new float[1024];
        when(gateway.embed(List.of("Spring AI embedding"))).thenReturn(List.of(vector));
        when(store.search(eq(workspaceId), eq("bge-m3"), eq(vector), eq(8), eq(0.35)))
                .thenReturn(List.of());

        var response = new KnowledgeSearchService(store, gateway, properties,
                Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC))
                .search(workspaceId, "Spring AI embedding", 8);

        assertThat(response.model()).isEqualTo("bge-m3");
        verify(store).recordRetrieval(eq(null), eq(workspaceId), eq("Spring AI embedding"),
                eq("VECTOR"), eq(0), anyLong(), anyList(), any());
    }

    @Test
    void rejectsSearchWhenEmbeddingIsDisabled() {
        var service = new KnowledgeSearchService(mock(KnowledgeEmbeddingStore.class),
                mock(TextEmbeddingGateway.class), properties(false));
        assertThatThrownBy(() -> service.search(UUID.randomUUID(), "query", 8))
                .isInstanceOf(KnowledgeSearchService.EmbeddingUnavailableException.class);
    }

    private static KnowledgeEmbeddingProperties properties(boolean enabled) {
        var properties = new KnowledgeEmbeddingProperties();
        properties.setEnabled(enabled);
        return properties;
    }
}
