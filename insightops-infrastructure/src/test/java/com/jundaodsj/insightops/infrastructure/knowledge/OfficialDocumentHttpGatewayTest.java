package com.jundaodsj.insightops.infrastructure.knowledge;

import com.jundaodsj.insightops.knowledge.application.DocumentCollectionException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.OfficialDocumentGateway;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficialDocumentHttpGatewayTest {
    @org.junit.jupiter.api.Test
    void versionLabelsComeFromVersionedOfficialPathsRatherThanCurrentDocs() {
        assertThat(OfficialDocumentHttpGateway.version(java.net.URI.create("https://blog.vuejs.org/posts/vue-3-5"))).isEqualTo("3.5");
        assertThat(OfficialDocumentHttpGateway.version(java.net.URI.create("https://vuejs.org/guide/components/props.html"))).isNull();
        assertThat(OfficialDocumentHttpGateway.version(java.net.URI.create("https://example.org/posts/vue-3-5"))).isNull();
        assertThat(OfficialDocumentHttpGateway.version(java.net.URI.create("https://docs.spring.io/spring-ai/reference/1.1/index.html"))).isEqualTo("1.1");
        assertThat(OfficialDocumentHttpGateway.version(java.net.URI.create("https://raw.githubusercontent.com/vuejs/core/v3.5.1/CHANGELOG.md"))).isEqualTo("3.5.1");
        assertThat(OfficialDocumentHttpGateway.version(java.net.URI.create("https://raw.githubusercontent.com/vuejs/core/main/CHANGELOG.md"))).isNull();
    }

    @Test
    void pinnedChangelogKeepsOnlyTheNamedReleaseWithoutRelabelingOlderChanges() {
        String changelog = """
                ## [3.5.1](https://example.test/compare) (2024-09-04)

                ### Bug Fixes
                * reactivity: handle non-array arguments in concat

                # [3.5.0](https://example.test/compare) (2024-09-03)

                ## Aggregated Features List for 3.5
                * compiler-sfc: enable reactive props destructure by default

                # [3.4.38](https://example.test/compare) (2024-08-15)
                * older release
                """;
        assertThat(OfficialDocumentHttpGateway.releaseSection(changelog, "3.5.1"))
                .contains("non-array arguments").doesNotContain("3.5.0", "reactive props destructure");
        assertThat(OfficialDocumentHttpGateway.releaseSection(changelog, "3.5.0"))
                .contains("reactive props destructure").doesNotContain("non-array arguments", "older release");
        assertThatThrownBy(() -> OfficialDocumentHttpGateway.releaseSection(changelog, "3.5.2"))
                .isInstanceOf(DocumentCollectionException.class).hasMessageContaining("requested version");
    }
    private final OfficialDocumentHttpGateway gateway =
            new OfficialDocumentHttpGateway(new KnowledgeDocumentChunker());

    @Test
    void rejectsUnregisteredHostsBeforeAnyNetworkRequest() {
        KnowledgeStore.SourceTask source = source("https://evil.example/docs");

        assertThatThrownBy(() -> gateway.collect(source, options(), progress -> { }))
                .isInstanceOfSatisfying(DocumentCollectionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(DocumentCollectionException.Code.VALIDATION_ERROR));
    }

    @Test
    void rejectsEncodedParentPathSegmentsBeforeAnyNetworkRequest() {
        KnowledgeStore.SourceTask source = source(
                "https://docs.spring.io/spring-ai/reference/%2e%2e/actuator");

        assertThatThrownBy(() -> gateway.collect(source, options(), progress -> { }))
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

    @Test
    void removesDocumentationIndexBoilerplateFromMarkdown() {
        String cleaned = OfficialDocumentHttpGateway.cleanMarkdown("""
                # Useful page

                > ## Documentation Index
                > Fetch the complete documentation index at: https://docs.dify.ai/llms.txt
                > Use this file to discover all available pages before exploring further.

                The actual official documentation remains available.
                """);

        assertThat(cleaned).contains("# Useful page", "actual official documentation");
        assertThat(cleaned).doesNotContain("Documentation Index", "llms.txt");
        assertThat(OfficialDocumentHttpGateway.isBoilerplate(
                "For the latest stable version, please use Spring AI 2.0.0!")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsCurrentUrlAndMonotonicCrawlProgress() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> robots = mock(HttpResponse.class);
        when(robots.statusCode()).thenReturn(404);
        when(robots.body()).thenReturn(stream("not found"));

        HttpResponse<InputStream> page = mock(HttpResponse.class);
        when(page.statusCode()).thenReturn(200);
        when(page.body()).thenReturn(stream("""
                <html lang="en"><head><title>Spring AI</title></head>
                <body><main><h1>Spring AI Reference</h1><p>Official documentation content.</p></main></body></html>
                """));
        when(page.headers()).thenReturn(HttpHeaders.of(
                Map.of("content-type", List.of("text/html; charset=utf-8")), (left, right) -> true));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(robots, page);

        var testGateway = new OfficialDocumentHttpGateway(http, new KnowledgeDocumentChunker());
        List<KnowledgeStore.CollectionProgress> progress = new ArrayList<>();
        var pages = testGateway.collect(source("https://docs.spring.io/spring-ai/reference/"),
                options(), progress::add);

        assertThat(pages).hasSize(1);
        assertThat(progress).isNotEmpty();
        assertThat(progress.getLast().currentUrl())
                .isEqualTo("https://docs.spring.io/spring-ai/reference/");
        assertThat(progress.getLast().visitedUrlCount()).isEqualTo(1);
        assertThat(progress.getLast().collectedPageCount()).isEqualTo(1);
        assertThat(progress).extracting(KnowledgeStore.CollectionProgress::maxPageCount)
                .containsOnly(1);
    }

    private static InputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectsExactSinglePageRootWithoutFollowingAdjacentPaths() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> robots = mock(HttpResponse.class);
        when(robots.statusCode()).thenReturn(404);
        when(robots.body()).thenReturn(stream("not found"));
        HttpResponse<InputStream> page = mock(HttpResponse.class);
        when(page.statusCode()).thenReturn(200);
        when(page.body()).thenReturn(stream("""
                <main><h1>Release overview</h1><p>Verified release details.</p>
                <a href="/spring-ai/reference/page.html-other">Outside boundary</a></main>
                """));
        when(page.headers()).thenReturn(HttpHeaders.of(Map.of("content-type", List.of("text/html")), (left, right) -> true));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(robots, page);
        String root = "https://docs.spring.io/spring-ai/reference/page.html";
        var singlePage = new KnowledgeStore.SourceTask(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Spring AI", "single", "Single article", "OFFICIAL_DOCUMENTATION", root, root,
                "docs.spring.io", "/spring-ai/reference/page.html/", "T1_PROJECT_DOMAIN", 0);
        var pages = new OfficialDocumentHttpGateway(http, new KnowledgeDocumentChunker()).collect(singlePage,
                new OfficialDocumentGateway.CrawlOptions(5, 2, 65_536, Duration.ofSeconds(1), Duration.ZERO, 600, 80), progress -> { });
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().canonicalUrl()).isEqualTo(root);
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
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
