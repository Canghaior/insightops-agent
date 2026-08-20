package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void resolvesConfiguredProjectAliasesAgainstMatchingEvidence() {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        AdminProjectStore projects = mock(AdminProjectStore.class);
        when(projects.list(workspaceId)).thenReturn(List.of(project(projectId)));
        KnowledgeAnswerabilityPolicy dynamic = new KnowledgeAnswerabilityPolicy(projects);

        assertThat(dynamic.assess(workspaceId, "Gemini Java 的流式接口怎么使用？",
                List.of(result(projectId, "java-genai", "T1_PROJECT_DOMAIN"))).answerable()).isTrue();
        assertThat(dynamic.assess(workspaceId, "Gemini Java 的流式接口怎么使用？",
                List.of(result(UUID.randomUUID(), "other", "T1_PROJECT_DOMAIN"))).answerable()).isFalse();
    }

    @Test
    void allowsRelevantVisibleUserUploadWithoutRequiringAProjectName() {
        assertThat(policy.assess(UUID.randomUUID(), "总结这份团队架构方案",
                List.of(result(UUID.randomUUID(), "spring-ai", "T2_USER_UPLOAD"))).answerable()).isTrue();
    }

    private static AdminProjectStore.ManagedProject project(UUID projectId) {
        return new AdminProjectStore.ManagedProject(projectId, "github", "googleapis", "java-genai",
                "https://github.com/googleapis/java-genai", 2, 12, List.of("gemini java"),
                true, "SUCCEEDED", Instant.EPOCH, Instant.EPOCH, 0, null,
                0, 1, 0, 0, Instant.EPOCH, Instant.EPOCH);
    }

    private static KnowledgeEmbeddingStore.SearchResult result(String project) {
        return result(UUID.randomUUID(), project, "T1_PROJECT_DOMAIN");
    }

    private static KnowledgeEmbeddingStore.SearchResult result(UUID projectId, String project, String trustTier) {
        return new KnowledgeEmbeddingStore.SearchResult(UUID.randomUUID(), projectId,
                project, project + " docs", "Reference", "https://docs.example.test", "Guide",
                "official content", "en", trustTier, 0.8);
    }
}
