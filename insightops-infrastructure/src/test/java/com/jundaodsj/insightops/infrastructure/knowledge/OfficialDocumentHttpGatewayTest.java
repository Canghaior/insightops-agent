package com.jundaodsj.insightops.infrastructure.knowledge;

import com.jundaodsj.insightops.knowledge.application.DocumentCollectionException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.OfficialDocumentGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialDocumentHttpGatewayTest {
    private final OfficialDocumentHttpGateway gateway =
            new OfficialDocumentHttpGateway(new KnowledgeDocumentChunker());

    @Test
    void rejectsUnregisteredHostsBeforeAnyNetworkRequest() {
        KnowledgeStore.SourceTask source = source("https://evil.example/docs");

        assertThatThrownBy(() -> gateway.collect(source, options()))
                .isInstanceOfSatisfying(DocumentCollectionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(DocumentCollectionException.Code.VALIDATION_ERROR));
    }

    @Test
    void rejectsEncodedParentPathSegmentsBeforeAnyNetworkRequest() {
        KnowledgeStore.SourceTask source = source(
                "https://docs.spring.io/spring-ai/reference/%2e%2e/actuator");

        assertThatThrownBy(() -> gateway.collect(source, options()))
                .isInstanceOfSatisfying(DocumentCollectionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(DocumentCollectionException.Code.VALIDATION_ERROR));
    }

    @Test
    void honorsLongestMatchingRobotsRule() {
        var rules = OfficialDocumentHttpGateway.RobotsRules.parse("""
                User-agent: *
                Disallow: /private
                Allow: /private/public
                """);

        assertThat(rules.allowed("/reference/index.html")).isTrue();
        assertThat(rules.allowed("/private/secret.html")).isFalse();
        assertThat(rules.allowed("/private/public/index.html")).isTrue();
    }

    private static KnowledgeStore.SourceTask source(String discoveryUrl) {
        return new KnowledgeStore.SourceTask(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Spring AI", "spring-ai-documentation", "Spring AI Reference",
                "OFFICIAL_DOCUMENTATION", "https://docs.spring.io/spring-ai/reference/", discoveryUrl,
                "docs.spring.io", "/spring-ai/reference/", "T1_PROJECT_DOMAIN", 0);
    }

    private static OfficialDocumentGateway.CrawlOptions options() {
        return new OfficialDocumentGateway.CrawlOptions(1, 0, 65_536,
                Duration.ofSeconds(1), Duration.ZERO, 600, 80);
    }
}
