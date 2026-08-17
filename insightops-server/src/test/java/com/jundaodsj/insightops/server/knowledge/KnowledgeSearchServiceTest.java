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
        var result = result("Spring AI");
        when(store.search(eq(workspaceId), eq("bge-m3"), eq(vector), eq(16), eq(0.35)))
                .thenReturn(List.of(result));
        when(store.searchKeyword(workspaceId, "Spring AI embedding", 16))
                .thenReturn(List.of(result));

        var response = new KnowledgeSearchService(store, gateway, properties,
                new HybridSearchRanker(),
                Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC))
                .search(workspaceId, "Spring AI embedding", 8);

        assertThat(response.model()).isEqualTo("bge-m3+fts");
        assertThat(response.mode()).isEqualTo("HYBRID");
        assertThat(response.results()).hasSize(1);
        verify(store).recordRetrieval(eq(null), eq(workspaceId), eq("Spring AI embedding"),
                eq("HYBRID"), eq(1), anyLong(), anyList(), any());
    }

    @Test
    void fallsBackToKeywordWhenEmbeddingIsDisabled() {
        KnowledgeEmbeddingStore store = mock(KnowledgeEmbeddingStore.class);
        UUID workspaceId = UUID.randomUUID();
        when(store.searchKeyword(workspaceId, "query", 16)).thenReturn(List.of(result("Dify")));
        var service = new KnowledgeSearchService(store,
                mock(TextEmbeddingGateway.class), properties(false), new HybridSearchRanker());

        var response = service.search(workspaceId, "query", 8);

        assertThat(response.mode()).isEqualTo("KEYWORD");
        assertThat(response.provider()).isEqualTo("postgresql");
        assertThat(response.model()).isEqualTo("fts");
        assertThat(response.vectorAvailable()).isFalse();
        assertThat(response.results()).hasSize(1);
    }

    @Test
    void reportsVectorOnlyModeWithoutAdvertisingFullTextSearch() {
        KnowledgeEmbeddingStore store = mock(KnowledgeEmbeddingStore.class);
        TextEmbeddingGateway gateway = mock(TextEmbeddingGateway.class);
        UUID workspaceId = UUID.randomUUID();
        float[] vector = new float[1024];
        when(gateway.embed(List.of("纯语义问题"))).thenReturn(List.of(vector));
        when(store.search(eq(workspaceId), eq("bge-m3"), eq(vector), eq(16), eq(0.35)))
                .thenReturn(List.of(result("Spring AI")));
        when(store.searchKeyword(workspaceId, "纯语义问题", 16)).thenReturn(List.of());

        var response = new KnowledgeSearchService(store, gateway, properties(true),
                new HybridSearchRanker()).search(workspaceId, "纯语义问题", 8);

        assertThat(response.mode()).isEqualTo("VECTOR");
        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(response.model()).isEqualTo("bge-m3");
    }

    @Test
    void rejectsSearchWhenBothRetrievalChannelsAreUnavailable() {
        KnowledgeEmbeddingStore store = mock(KnowledgeEmbeddingStore.class);
        when(store.searchKeyword(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("database unavailable"));
        var service = new KnowledgeSearchService(store,
                mock(TextEmbeddingGateway.class), properties(false), new HybridSearchRanker());
        assertThatThrownBy(() -> service.search(UUID.randomUUID(), "query", 8))
                .isInstanceOf(KnowledgeSearchService.EmbeddingUnavailableException.class);
    }

    private static KnowledgeEmbeddingStore.SearchResult result(String project) {
        return new KnowledgeEmbeddingStore.SearchResult(
                UUID.randomUUID(), UUID.randomUUID(), project, project + " docs",
                project + " title", "https://docs.example.test/page", "heading", "content",
                "en", "T1_PROJECT_DOMAIN", 0.8);
    }

    private static KnowledgeEmbeddingProperties properties(boolean enabled) {
        var properties = new KnowledgeEmbeddingProperties();
        properties.setEnabled(enabled);
        return properties;
    }
}
