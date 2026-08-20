package com.jundaodsj.insightops.infrastructure.knowledge;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficialUpdateGatewayTest {

    @Test
    @SuppressWarnings("unchecked")
    void collectsRssEntriesWithinRegisteredBoundary() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> feed = response(200, "application/rss+xml", """
                <rss><channel><item><guid>one</guid><title>Spring AI agent update</title>
                <link>https://spring.io/blog/2026/agent-update</link>
                <description>Official agent update with enough technical content for retrieval.</description>
                </item></channel></rss>
                """, Map.of("etag", List.of("\"feed-v1\"")));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(feed);
        var gateway = new OfficialDocumentHttpGateway(http, new KnowledgeDocumentChunker());

        var pages = gateway.collect(source("OFFICIAL_BLOG_RSS", "spring.io", "/blog/",
                "https://spring.io/blog.atom", "https://spring.io/blog.atom"), options(), ignored -> { });

        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().canonicalUrl()).isEqualTo("https://spring.io/blog/2026/agent-update");
        assertThat(pages.getFirst().etag()).isEqualTo("\"feed-v1\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectsGitHubMilestonesAsRoadmapDocuments() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = response(200, "application/json", """
                [{"number":2,"title":"2.0 GA","state":"open","description":"Java agent roadmap milestone",
                  "open_issues":3,"closed_issues":8,"updated_at":"2026-08-19T00:00:00Z",
                  "html_url":"https://github.com/openai/openai-java/milestone/2"}]
                """, Map.of());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var gateway = new OfficialDocumentHttpGateway(http, new KnowledgeDocumentChunker());

        var pages = gateway.collect(source("OFFICIAL_ROADMAP", "api.github.com",
                "/repos/openai/openai-java/milestones/",
                "https://api.github.com/repos/openai/openai-java/milestones",
                "https://api.github.com/repos/openai/openai-java/milestones"), options(), ignored -> { });

        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().canonicalUrl()).isEqualTo("https://github.com/openai/openai-java/milestone/2");
        assertThat(pages.getFirst().contentText()).contains("Open issues: 3", "Java agent roadmap milestone");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsCrossRepositoryMilestoneCanonicalUrl() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = response(200, "application/json", """
                [{"number":2,"title":"2.0 GA","state":"open","description":"Roadmap",
                  "open_issues":3,"closed_issues":8,"updated_at":"2026-08-19T00:00:00Z",
                  "html_url":"https://github.com/attacker/other/milestone/2"}]
                """, Map.of());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var gateway = new OfficialDocumentHttpGateway(http, new KnowledgeDocumentChunker());

        var pages = gateway.collect(source("OFFICIAL_ROADMAP", "api.github.com",
                "/repos/openai/openai-java/milestones",
                "https://api.github.com/repos/openai/openai-java/milestones",
                "https://api.github.com/repos/openai/openai-java/milestones"), options(), ignored -> { });

        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().canonicalUrl())
                .isEqualTo("https://api.github.com/repos/openai/openai-java/milestones/2");
    }

    private static KnowledgeStore.SourceTask source(String type, String host, String prefix,
                                                     String root, String discovery) {
        return new KnowledgeStore.SourceTask(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "project", "source", "Official updates", type,
                root, discovery, host, prefix, "T1_PROJECT_DOMAIN", 12, 0);
    }

    private static OfficialDocumentGateway.CrawlOptions options() {
        return new OfficialDocumentGateway.CrawlOptions(20, 0, 65_536,
                Duration.ofSeconds(1), Duration.ZERO, 600, 80);
    }

    private static HttpResponse<InputStream> response(int status, String contentType, String body,
                                                       Map<String, List<String>> extra) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        java.util.HashMap<String, List<String>> headers = new java.util.HashMap<>(extra);
        headers.put("content-type", List.of(contentType));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (left, right) -> true));
        return response;
    }
}
