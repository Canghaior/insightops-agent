package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.knowledge.application.RagEvaluationStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RagQualityMetricsTest {

    @Test
    void scoresNormalizedProjectRankTermsAndCitations() {
        var item = new RagEvaluationDataset.EvaluationCase("spring-1", "Spring AI ChatClient",
                true, "spring-ai", "api", List.of("chatclient", "builder"), List.of(),
                "docs.spring.io", "verified");
        var response = new KnowledgeSearchService.SearchResponse("q", "ollama", "bge-m3", 9,
                List.of(result("Dify", "workflow"), result("Spring AI", "ChatClient builder API")));

        var retrieval = RagQualityMetrics.retrieval(item, response,
                new KnowledgeAnswerabilityPolicy());
        var citations = RagQualityMetrics.citations("结论 [S1]，补充 [S2]，错误 [S9]。", 2);

        assertThat(retrieval.projectHit()).isTrue();
        assertThat(retrieval.reciprocalRank()).isEqualTo(0.5);
        assertThat(retrieval.termCoverage()).isEqualTo(1.0);
        assertThat(citations.precision()).isCloseTo(2.0 / 3.0,
                org.assertj.core.data.Offset.offset(0.0001));
        assertThat(citations.coverage()).isEqualTo(1.0);
    }

    @Test
    void aggregateFailsWhenARequiredRetrievalGateMisses() {
        RagEvaluationProperties properties = new RagEvaluationProperties();
        List<RagEvaluationStore.CaseResult> cases = List.of(
                new RagEvaluationStore.CaseResult("positive", "q", true, "spring-ai",
                        true, true, false, 0, 1, "HYBRID", List.of(), List.of(),
                        null, null, null, null, null),
                new RagEvaluationStore.CaseResult("negative", "q2", false, null,
                        false, true, false, 0, 1, "HYBRID", List.of(), List.of(),
                        null, null, null, null, null));

        var summary = RagQualityMetrics.aggregate(cases, properties, null);

        assertThat(summary.recallAtK()).isZero();
        assertThat(summary.noAnswerAccuracy()).isEqualTo(1.0);
        assertThat(summary.passed()).isFalse();
    }

    private static KnowledgeEmbeddingStore.SearchResult result(String project, String content) {
        return new KnowledgeEmbeddingStore.SearchResult(UUID.randomUUID(), UUID.randomUUID(),
                project, project + " docs", "Reference", "https://docs.example.test/" + project,
                "Guide", content, "en", "T1_PROJECT_DOMAIN", 0.8);
    }
}
