package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeAnswerabilityPolicyTest {
    private final KnowledgeAnswerabilityPolicy policy = new KnowledgeAnswerabilityPolicy();

    @Test
    void requiresAnExplicitSupportedProjectAndMatchingEvidence() {
        assertThat(policy.assess("Spring AI ChatClient 怎么使用？", List.of(result("Spring AI")))
                .answerable()).isTrue();
        assertThat(policy.assess("LangChain4j AI Services 怎么使用？", List.of(result("Dify")))
                .answerable()).isFalse();
        assertThat(policy.assess("Kubernetes Ingress TLS 怎么配置？", List.of(result("Spring AI")))
                .answerable()).isFalse();
        assertThat(policy.assess("Dify 工作流怎么发布？", List.of()).answerable()).isFalse();
    }

    private static KnowledgeEmbeddingStore.SearchResult result(String project) {
        return new KnowledgeEmbeddingStore.SearchResult(UUID.randomUUID(), UUID.randomUUID(),
                project, project + " docs", "Reference", "https://docs.example.test", "Guide",
                "official content", "en", "T1_PROJECT_DOMAIN", 0.8);
    }
}
