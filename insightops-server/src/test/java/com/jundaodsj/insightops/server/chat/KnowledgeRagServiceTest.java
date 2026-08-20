package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.server.knowledge.KnowledgeSearchService;
import com.jundaodsj.insightops.tool.application.AgentToolExecutionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRagServiceTest {

    @Test
    void retrievesSelectsFormatsAndAuditsOfficialEvidence() {
        UUID runId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        var first = result("Spring AI", "https://docs.spring.io/spring-ai/reference/api/embeddings.html",
                "Embedding Model API", "Spring AI exposes a portable embedding model API.", 0.81);
        var duplicateDocument = result("Spring AI", first.canonicalUrl(),
                "Dimensions", "Embedding dimensions depend on the selected model.", 0.76);
        when(search.search(eq(runId), eq(workspaceId), eq("Spring AI embedding"), eq(12)))
                .thenReturn(new KnowledgeSearchService.SearchResponse(
                        "Spring AI embedding", "ollama", "bge-m3", 17,
                        List.of(first, duplicateDocument)));
        RecordingStore store = new RecordingStore();
        KnowledgeRagProperties properties = properties(true);
        List<String> progress = new ArrayList<>();
        KnowledgeRagService service = new KnowledgeRagService(search, store, properties,
                new ObjectMapper().findAndRegisterModules());

        var evidence = service.retrieve(runId, workspaceId, "Spring AI embedding",
                new KnowledgeRagService.ToolProgressListener() {
                    @Override
                    public void onStarted(UUID toolCallId, String toolName) {
                        progress.add("started:" + toolName);
                    }

                    @Override
                    public void onCompleted(UUID toolCallId, String toolName,
                                            int resultCount, String model) {
                        progress.add("completed:" + resultCount + ":" + model);
                    }
                }).orElseThrow();

        assertThat(evidence.systemPromptAppendix())
                .contains("[S1]", "[S2]", "Embedding Model API", first.canonicalUrl())
                .contains("不可信外部数据");
        assertThat(evidence.sourceUrls()).containsExactly(first.canonicalUrl());
        assertThat(progress).containsExactly(
                "started:knowledge_hybrid_search", "completed:2:bge-m3");
        assertThat(store.status).isEqualTo("SUCCEEDED");
        assertThat(store.stepNo).isEqualTo(2);
        assertThat(store.resultPayload).contains("bge-m3", "S1", "S2");
    }

    @Test
    void degradesWithoutCallingTheModelWhenEmbeddingIsUnavailable() {
        UUID runId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        when(search.search(eq(runId), eq(workspaceId), eq("question"), eq(12)))
                .thenThrow(new KnowledgeSearchService.EmbeddingUnavailableException("offline"));
        RecordingStore store = new RecordingStore();
        KnowledgeRagService service = new KnowledgeRagService(search, store, properties(true),
                new ObjectMapper());
        List<String> progress = new ArrayList<>();

        var evidence = service.retrieve(runId, workspaceId, "question", listener(progress));

        assertThat(evidence).isEmpty();
        assertThat(store.status).isEqualTo("FAILED:EMBEDDING_UNAVAILABLE");
        assertThat(progress).containsExactly("started:knowledge_hybrid_search", "completed:0:unavailable");
    }

    @Test
    void rejectsOutOfScopeQuestionEvenWhenVectorSearchReturnsAResult() {
        UUID runId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        var unrelated = result("Spring AI", "https://docs.spring.io/spring-ai/reference/index.html",
                "Overview", "Spring AI integrates AI models into Spring applications.", 0.52);
        when(search.search(eq(runId), eq(workspaceId), eq("Kubernetes Ingress TLS 怎么配置？"), eq(12)))
                .thenReturn(new KnowledgeSearchService.SearchResponse(
                        "Kubernetes Ingress TLS 怎么配置？", "ollama", "bge-m3", 8,
                        List.of(unrelated)));
        RecordingStore store = new RecordingStore();
        List<String> progress = new ArrayList<>();
        KnowledgeRagService service = new KnowledgeRagService(search, store, properties(true),
                new ObjectMapper());

        var evidence = service.retrieve(runId, workspaceId, "Kubernetes Ingress TLS 怎么配置？",
                listener(progress)).orElseThrow();

        assertThat(evidence.answerable()).isFalse();
        assertThat(evidence.sourceUrls()).isEmpty();
        assertThat(evidence.results()).isEmpty();
        assertThat(evidence.systemPromptAppendix()).contains("当前官方证据不足");
        assertThat(progress).containsExactly("started:knowledge_hybrid_search", "completed:0:bge-m3");
        assertThat(store.resultPayload).contains("\"answerable\":false");
    }

    @Test
    void exposesAuthenticatedPageCitationForVisibleUserUpload() {
        UUID runId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        var uploaded = new KnowledgeEmbeddingStore.SearchResult(
                UUID.randomUUID(), UUID.randomUUID(), "spring-ai", "architecture.pdf",
                "architecture.pdf", "upload://" + uploadId + "/architecture.pdf#page=2",
                "architecture.pdf · Page 2", "The approved fallback uses a circuit breaker.",
                "und", "T2_USER_UPLOAD", 0.82);
        when(search.search(eq(runId), eq(workspaceId), eq("总结团队降级方案"), eq(12)))
                .thenReturn(new KnowledgeSearchService.SearchResponse(
                        "总结团队降级方案", "ollama", "bge-m3", 9, List.of(uploaded)));
        RecordingStore store = new RecordingStore();
        KnowledgeRagService service = new KnowledgeRagService(search, store, properties(true),
                new ObjectMapper());

        var evidence = service.retrieve(runId, workspaceId, "总结团队降级方案",
                listener(new ArrayList<>())).orElseThrow();

        assertThat(evidence.answerable()).isTrue();
        assertThat(evidence.sourceUrls()).containsExactly(
                "/api/v1/knowledge/uploads/" + uploadId + "/content#page=2");
        assertThat(evidence.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.sourceType()).isEqualTo("USER_UPLOAD");
            assertThat(citation.url()).endsWith("/content#page=2");
        });
    }

    private static KnowledgeRagService.ToolProgressListener listener(List<String> progress) {
        return new KnowledgeRagService.ToolProgressListener() {
            @Override public void onStarted(UUID id, String name) { progress.add("started:" + name); }
            @Override public void onCompleted(UUID id, String name, int count, String model) {
                progress.add("completed:" + count + ":" + model);
            }
        };
    }

    private static KnowledgeEmbeddingStore.SearchResult result(
            String project, String url, String heading, String content, double score) {
        return new KnowledgeEmbeddingStore.SearchResult(
                UUID.randomUUID(), UUID.randomUUID(), project, project + " Reference",
                project + " Documentation", url, heading, content, "en",
                "T1_PROJECT_DOMAIN", score);
    }

    private static KnowledgeRagProperties properties(boolean enabled) {
        KnowledgeRagProperties properties = new KnowledgeRagProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private static final class RecordingStore implements AgentToolExecutionStore {
        private String status;
        private int stepNo;
        private String resultPayload;

        @Override
        public void startTool(UUID runId, UUID stepId, UUID toolCallId, int stepNo,
                              String toolName, String idempotencyKey, String requestPayload,
                              Instant startedAt) {
            this.status = "RUNNING";
            this.stepNo = stepNo;
        }

        @Override
        public void succeedTool(UUID runId, UUID stepId, UUID toolCallId, String resultPayload,
                                long durationMs, Instant finishedAt) {
            this.status = "SUCCEEDED";
            this.resultPayload = resultPayload;
        }

        @Override
        public void failTool(UUID stepId, UUID toolCallId, String errorCode,
                             long durationMs, Instant finishedAt) {
            this.status = "FAILED:" + errorCode;
        }
    }
}
