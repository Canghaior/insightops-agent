package com.jundaodsj.insightops.server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.QualityReviewStore;
import com.jundaodsj.insightops.knowledge.application.RagEvaluationStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.model.application.ChatModelGateway;
import com.jundaodsj.insightops.model.application.ChatModelResponse;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagEvaluationServiceVersionTest {

    @SuppressWarnings("unchecked")
    @Test void requiredFinalAnswerTextIsSavedAndBlocksTheGateEvenWhenRetrievalAndCitationMetricsPass() {
        for (boolean includeRequired : List.of(false, true)) {
            UUID workspace = UUID.randomUUID();
            var dataset = mock(RagEvaluationDataset.class);
            when(dataset.load()).thenReturn(List.of(new RagEvaluationDataset.EvaluationCase("required-answer", "Spring AI EmbeddingModel",
                    true, "spring-ai", "final-answer", List.of("EmbeddingModel"), List.of("EmbeddingModel", "调用 embed"), "docs.spring.io", "verified")));
            var evidence = new KnowledgeEmbeddingStore.SearchResult(UUID.randomUUID(), UUID.randomUUID(), "Spring AI", "EmbeddingModel", "API",
                    "https://docs.spring.io/spring-ai/reference/", "Reference", "EmbeddingModel 调用 embed", "zh", "T1_PROJECT_DOMAIN", 1.0);
            var search = mock(KnowledgeSearchService.class);
            when(search.search(eq(workspace), anyString(), eq(10))).thenReturn(new KnowledgeSearchService.SearchResponse("q", "test", "test", 1, List.of(evidence)));
            var store = mock(RagEvaluationStore.class);
            when(store.latest(workspace)).thenReturn(Optional.of(new RagEvaluationStore.Report(UUID.randomUUID(), "fixture", "DONE", 1, 1, null, null, Instant.now(), Instant.now(), List.of())));
            var model = mock(ChatModelGateway.class);
            when(model.generate(any())).thenReturn(new ChatModelResponse(includeRequired ? "EmbeddingModel 调用 embed [S1]" : "EmbeddingModel 可以使用 [S1]",
                    "fixture", "fixture", ModelUsage.unknown(), java.time.Duration.ofMillis(1)));
            ObjectProvider<ChatModelGateway> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(model);
            var service = new RagEvaluationService(dataset, search, new KnowledgeAnswerabilityPolicy(), store,
                    new RagEvaluationProperties(), mock(QualityReviewStore.class), provider, new ObjectMapper());
            service.run(workspace, 1, false);
            var result = org.mockito.ArgumentCaptor.forClass(RagEvaluationStore.CaseResult.class);
            verify(store).saveCase(any(), result.capture());
            assertThat(result.getValue().citationPrecision()).isEqualTo(1.0);
            assertThat(result.getValue().answerContract().passed()).isEqualTo(includeRequired);
            if (!includeRequired) assertThat(result.getValue().answerContract().missingRequired()).containsExactly("调用 embed");
            verify(store).complete(any(), argThat(summary -> summary.passed() == includeRequired), any());
            verify(model, times(1)).generate(any()); // Optional semantic judge stays separate and is not faked.
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void evaluatesBaseAndFeedbackCasesUnderTheVersionName() {
        UUID workspaceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        RagEvaluationDataset dataset = mock(RagEvaluationDataset.class);
        when(dataset.load()).thenReturn(List.of(new RagEvaluationDataset.EvaluationCase(
                "base-negative", "How do I cook pasta?", false, null,
                "scope-boundary", List.of(), List.of(), null, "verified")));
        QualityReviewStore qualityStore = mock(QualityReviewStore.class);
        when(qualityStore.datasetSelection(workspaceId, versionId)).thenReturn(Optional.of(
                new QualityReviewStore.DatasetSelection("p1-rag-feedback-v1", versionId, List.of(
                        new QualityReviewStore.DatasetCase(
                                "feedback-negative", "What is tomorrow's weather?", false, null,
                                "feedback-regression", List.of(), List.of(), null, "verified")))));
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        when(search.search(eq(workspaceId), anyString(), eq(10))).thenAnswer(invocation ->
                new KnowledgeSearchService.SearchResponse(invocation.getArgument(1),
                        "postgresql", "fts", "FTS", false, 1, List.of()));
        RagEvaluationStore store = mock(RagEvaluationStore.class);
        RagEvaluationStore.Report report = new RagEvaluationStore.Report(
                UUID.randomUUID(), "p1-rag-feedback-v1", "PASSED", 2, 0,
                null, null, Instant.now(), Instant.now(), List.of());
        when(store.latest(workspaceId)).thenReturn(Optional.of(report));
        ObjectProvider<ChatModelGateway> modelProvider = mock(ObjectProvider.class);

        RagEvaluationService service = new RagEvaluationService(
                dataset, search, new KnowledgeAnswerabilityPolicy(), store,
                new RagEvaluationProperties(), qualityStore, modelProvider,
                new ObjectMapper().findAndRegisterModules());

        var result = service.run(workspaceId, 0, false, versionId);

        assertThat(result.datasetName()).isEqualTo("p1-rag-feedback-v1");
        verify(store).start(any(UUID.class), eq(workspaceId), eq("p1-rag-feedback-v1"),
                eq(2), eq(0), any(Instant.class));
        verify(store, times(2)).saveCase(any(UUID.class), any(RagEvaluationStore.CaseResult.class));
        verify(store).complete(any(UUID.class), argThat(RagEvaluationStore.Summary::passed), any(Instant.class));
    }
}
