package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.knowledge.application.DocumentCollectionException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.OfficialDocumentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeCollectionRunnerTest {
    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final OfficialDocumentGateway gateway = mock(OfficialDocumentGateway.class);
    private final KnowledgeCollectionProperties properties = new KnowledgeCollectionProperties();
    private final KnowledgeStore.SourceTask source = new KnowledgeStore.SourceTask(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "Spring AI", "spring-ai-documentation", "Spring AI Reference", "OFFICIAL_DOCUMENTATION",
            "https://docs.spring.io/spring-ai/reference/", "https://docs.spring.io/spring-ai/reference/",
            "docs.spring.io", "/spring-ai/reference/", "T1_PROJECT_DOMAIN", 0);
    private KnowledgeCollectionRunner runner;

    @BeforeEach
    void setUp() {
        runner = new KnowledgeCollectionRunner(store, gateway, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(store.claimDueSources(any(), any(), anyInt())).thenReturn(List.of(source));
    }

    @Test
    void storesCollectedPagesAndSchedulesNextDailySync() {
        var page = new KnowledgeStore.DocumentPage("https://docs.spring.io/spring-ai/reference/", "Spring AI",
                "en", null, "a".repeat(64), "content", null, null, List.of());
        when(gateway.collect(eq(source), any())).thenReturn(List.of(page));
        when(store.completeSuccessfulSync(eq(source), any(), eq(NOW), any()))
                .thenReturn(new KnowledgeStore.SyncResult(1, 1, 0, 0, 3));

        var result = runner.collectDueSources();

        assertThat(result.succeededSources()).isEqualTo(1);
        assertThat(result.collectedPages()).isEqualTo(1);
        verify(store).completeSuccessfulSync(source, List.of(page), NOW, NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void validationFailureUsesLongRetryAndDoesNotLeakTheCause() {
        when(gateway.collect(eq(source), any())).thenThrow(new DocumentCollectionException(
                DocumentCollectionException.Code.VALIDATION_ERROR, "outside registered host"));
        ArgumentCaptor<Instant> retry = ArgumentCaptor.forClass(Instant.class);

        var result = runner.collectDueSources();

        assertThat(result.failedSources()).isEqualTo(1);
        verify(store).completeFailedSync(eq(source), eq("VALIDATION_ERROR"),
                eq("outside registered host"), eq(NOW), retry.capture());
        assertThat(retry.getValue()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }
}
