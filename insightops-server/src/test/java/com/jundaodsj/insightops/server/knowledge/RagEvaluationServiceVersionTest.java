package com.jundaodsj.insightops.server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.QualityReviewStore;
import com.jundaodsj.insightops.knowledge.application.RagEvaluationStore;
import com.jundaodsj.insightops.model.application.ChatModelGateway;
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
