package com.jundaodsj.insightops.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore;
import com.jundaodsj.insightops.agent.application.AgentOrchestrationStore;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.server.chat.AgentLoopService;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEvaluationServiceTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void fingerprintsTheCurrentToolContractWhenCreatingACandidate() {
        Fixture fixture = fixture();
        when(fixture.store.createCandidate(any(), any(), any(), any()))
                .thenAnswer(invocation -> candidate(invocation.getArgument(2)));

        AgentEvaluationStore.Candidate created = fixture.service.createCandidate(
                WORKSPACE, USER, new AgentEvaluationStore.CandidateDraft(
                        "candidate", "Prefer official evidence", "deepseek-v4-flash",
                        0.0, 1024, "ignored", null));

        assertThat(created.toolContractHash()).hasSize(64);
        assertThat(created.toolContractHash()).isEqualTo(fixture.service.defaults().toolContractHash());
    }

    @Test
    void rejectsUnknownToolsBeforePersistingADataset() {
        Fixture fixture = fixture();
        AgentEvaluationStore.DatasetDraft draft = new AgentEvaluationStore.DatasetDraft(
                "invalid", "", gate(), List.of(new AgentEvaluationStore.CaseDraft(
                "unknown-tool", "test", List.of("not_registered"), List.of(), List.of(),
                false, 3, 10_000, 2_000, BigDecimal.ONE, true, null)));

        assertThatThrownBy(() -> fixture.service.createDataset(WORKSPACE, USER, draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Agent tool");
    }

    @Test
    void runsRealLoopInReadOnlyEvaluationModeAndPersistsPassingSummary() {
        Fixture fixture = fixture();
        UUID datasetId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID evaluationId = UUID.randomUUID();
        AgentEvaluationStore.Dataset dataset = dataset(datasetId);
        AgentEvaluationStore.Candidate candidate = candidate(
                candidateId, fixture.service.defaults().toolContractHash());
        AgentEvaluationStore.EvaluationRun queued = queued(
                evaluationId, datasetId, candidateId, "QUEUED");
        when(fixture.store.findCandidate(WORKSPACE, candidateId)).thenReturn(Optional.of(candidate));
        when(fixture.store.queueEvaluation(any(), any(), any(), any(), any()))
                .thenReturn(queued);
        when(fixture.store.findEvaluation(WORKSPACE, evaluationId)).thenReturn(Optional.of(queued));
        when(fixture.store.findDataset(WORKSPACE, datasetId)).thenReturn(Optional.of(dataset));
        when(fixture.loopProvider.getIfAvailable()).thenReturn(fixture.loop);
        when(fixture.loop.run(any(), any(), any())).thenReturn(new AgentLoopService.LoopResult(
                "evidence", List.of("https://spring.io/projects/spring-ai"), List.of(),
                new ModelUsage(100, 50, 150, 0L, 0L), 1, false, UUID.randomUUID(),
                new AgentOrchestrationStore.BudgetSnapshot(
                        1, 1, 150, new BigDecimal("0.010000"), "ACTIVE", null)));
        when(fixture.store.inspectAgentRun(any())).thenReturn(
                new AgentEvaluationStore.RunFacts(
                        List.of("knowledge_hybrid_search"), "SUCCEEDED", 0, 0));

        fixture.service.startEvaluation(WORKSPACE, USER, datasetId, candidateId);

        ArgumentCaptor<AgentLoopService.LoopRequest> request =
                ArgumentCaptor.forClass(AgentLoopService.LoopRequest.class);
        verify(fixture.loop).run(request.capture(), any(), any());
        assertThat(request.getValue().evaluationMode()).isTrue();
        assertThat(request.getValue().plannerProfile().modelName()).isEqualTo("deepseek-v4-flash");
        ArgumentCaptor<AgentEvaluationStore.Summary> summary =
                ArgumentCaptor.forClass(AgentEvaluationStore.Summary.class);
        verify(fixture.store).completeEvaluation(
                org.mockito.ArgumentMatchers.eq(evaluationId), summary.capture(), any());
        assertThat(summary.getValue().passed()).isTrue();
        assertThat(summary.getValue().successRate()).isEqualTo(1.0);
    }

    private static Fixture fixture() {
        AgentEvaluationStore store = mock(AgentEvaluationStore.class);
        AgentRunQuery runQuery = mock(AgentRunQuery.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentLoopService> provider = mock(ObjectProvider.class);
        AgentLoopService loop = mock(AgentLoopService.class);
        Executor direct = Runnable::run;
        AgentEvaluationService service = new AgentEvaluationService(
                store, runQuery,
                new AgentToolRegistry(AgentToolRegistryConfiguration.definitions(true)),
                new ObjectMapper().findAndRegisterModules(), provider,
                new DeepSeekModelProperties(
                        true, "https://api.deepseek.com", "deepseek-v4-flash", false,
                        0.2, 4096, 4, 90, 2, false),
                direct, new AgentEvaluationMetrics(new SimpleMeterRegistry()));
        return new Fixture(store, provider, loop, service);
    }

    private static AgentEvaluationStore.Gate gate() {
        return new AgentEvaluationStore.Gate(
                1, 1, 1, 1, 90_000, 16_000, BigDecimal.ONE);
    }

    private static AgentEvaluationStore.Dataset dataset(UUID id) {
        AgentEvaluationStore.EvaluationCase item = new AgentEvaluationStore.EvaluationCase(
                UUID.randomUUID(), id, "spring-ai-search", "查询 Spring AI ChatClient",
                List.of("knowledge_hybrid_search"), List.of("user_memory_upsert"),
                List.of("spring.io"), false, 4, 90_000, 16_000,
                BigDecimal.ONE, true, null);
        return new AgentEvaluationStore.Dataset(
                id, WORKSPACE, "agent-core", "", 1, "LOCKED", gate(), USER,
                Instant.parse("2026-08-22T00:00:00Z"), List.of(item));
    }

    private static AgentEvaluationStore.Candidate candidate(UUID id, String toolHash) {
        return new AgentEvaluationStore.Candidate(
                id, WORKSPACE, "candidate", 1, "DRAFT", "Prefer official evidence",
                "deepseek-v4-flash", 0, 1024, toolHash, null, USER,
                Instant.parse("2026-08-22T00:00:00Z"), null, null);
    }

    private static AgentEvaluationStore.Candidate candidate(
            AgentEvaluationStore.CandidateDraft draft) {
        return new AgentEvaluationStore.Candidate(
                UUID.randomUUID(), WORKSPACE, draft.name(), 1, "DRAFT",
                draft.plannerPromptAppendix(), draft.modelName(), draft.temperature(),
                draft.maxOutputTokens(), draft.toolContractHash(), draft.basedOnId(), USER,
                Instant.parse("2026-08-22T00:00:00Z"), null, null);
    }

    private static AgentEvaluationStore.EvaluationRun queued(
            UUID id, UUID datasetId, UUID candidateId, String status) {
        return new AgentEvaluationStore.EvaluationRun(
                id, WORKSPACE, datasetId, "agent-core", 1, candidateId, "candidate", 1,
                null, status, null, null, null, USER, null, null,
                Instant.parse("2026-08-22T00:00:00Z"), List.of());
    }

    private record Fixture(
            AgentEvaluationStore store,
            ObjectProvider<AgentLoopService> loopProvider,
            AgentLoopService loop,
            AgentEvaluationService service) {
    }
}
