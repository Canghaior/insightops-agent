package com.jundaodsj.insightops.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcKnowledgeEmbeddingStoreTest {

    @Test
    void buildsAnOrExpressionFromTechnicalTermsInAMixedLanguageQuestion() {
        String expression = JdbcKnowledgeEmbeddingStore.keywordExpression(
                "请仅基于官方知识库说明 Spring AI EmbeddingModel 的核心接口和调用方法");

        assertThat(expression).isEqualTo("\"spring\" OR \"embeddingmodel\"");
    }

    @Test
    void ignoresNaturalLanguageNoiseAndHandlesQueriesWithoutTechnicalTerms() {
        assertThat(JdbcKnowledgeEmbeddingStore.keywordExpression(
                "Please explain the official documentation for Dify"))
                .isEqualTo("\"dify\"");
        assertThat(JdbcKnowledgeEmbeddingStore.keywordExpression("请说明它的核心价值")).isEmpty();
    }
}
